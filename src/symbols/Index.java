package symbols;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import compiler.Compiler;
import sqlite3.SqliteException;

/// Every symbol tuul can answer questions about, in the order it looks: the
/// project's own sources, the jars under `vendor/`, and the running JDK.
///
/// A symbol is answered from two places at once. The class file says what the
/// type *is* — javac's output for the project, a vendored jar for a dependency,
/// `jrt:/` for the JDK. The source says what it *means*, because doc comments
/// do not survive compilation: the file on disk for the project, the
/// `-sources.jar` for a dependency, `lib/src.zip` for the JDK.
///
/// All of that is expensive and almost none of it changes, so the answers are
/// kept in a [Store] between runs. A lookup starts there, and only falls
/// through to javac when the sources have moved on. The compile is lazy for the
/// same reason: a question the index can answer must not pay for one.
public final class Index implements Catalog {

    /// Derived data, and deliberately under `build/`: deleting it costs the
    /// time to build it again and nothing else.
    public static final Path INDEX = Path.of("build", "index.db");

    /// One of the places symbols come from, and what it holds at the top.
    ///
    /// `name` is what the root is called in a URL or a message; `label` is what
    /// a reader is shown. They differ because `platform` is a good key and a
    /// poor heading.
    public static final String PROJECT = "project";

    public static final String DEPENDENCIES = "dependencies";

    public static final String PLATFORM = "platform";

    /// A symbol the search found: what it is called, what kind of thing it is,
    /// how it was declared, and what it says about itself.
    ///
    /// `modifiers` is the space-separated list the index holds, and is empty for
    /// a symbol that was declared with none. It is here so that a result can say
    /// `static` without the page being opened; it can never say `private`,
    /// because a private member is not indexed.
    private final List<Path> roots;
    private final Vendor vendor;
    private final Optional<IndexStore> store;
    private final String stamp;
    private final Compiler compiler;
    private Map<String, byte[]> classes;

    /// Worked out once: the roots do not change while a server is running, and
    /// walking the JDK's modules for every page would be a walk per page.
    private List<Catalog.Root> groups;

    private Index(List<Path> roots, Vendor vendor, Optional<IndexStore> store, String stamp, Compiler compiler) {
        this.roots = List.copyOf(roots);
        this.vendor = vendor;
        this.store = store;
        this.stamp = stamp;
        this.compiler = compiler;
    }

    public static Index of(List<Path> sourceRoots) throws IOException {
        return of(sourceRoots, List.of());
    }

    public static Index of(List<Path> sourceRoots, List<Path> vendorRoots) throws IOException {
        return of(sourceRoots, vendorRoots, INDEX);
    }

    /// The project as it stands: its sources compiled against its dependencies,
    /// and those dependencies available to be asked about themselves.
    public static Index of(List<Path> sourceRoots, List<Path> vendorRoots, Path index) throws IOException {
        return of(sourceRoots, vendorRoots, index, Compiler.system());
    }

    /// Opens an index that compiles project sources through `compiler`.
    /// Dependency and platform class files still come from their archives.
    public static Index of(List<Path> sourceRoots, List<Path> vendorRoots, Path index, Compiler compiler)
            throws IOException {
        var vendor = Vendor.of(vendorRoots);
        return new Index(sourceRoots, vendor, IndexStore.open(index), stamp(sourceRoots, vendor), compiler);
    }

    /// Opens an index with a caller-supplied compiler and store. Closing the
    /// index closes the store.
    public static Index of(List<Path> sourceRoots, List<Path> vendorRoots, Compiler compiler, IndexStore store)
            throws IOException {
        var vendor = Vendor.of(vendorRoots);
        return new Index(sourceRoots, vendor, Optional.of(store), stamp(sourceRoots, vendor), compiler);
    }

    /// Looks up a symbol by name. `a.b.Outer.Inner` also matches the nested
    /// type `a.b.Outer$Inner`, because that is how people write it.
    ///
    /// A name that is no type at all may still be a package or a module, and
    /// those are asked for last: a type wins a collision, since a package named
    /// after a type is a thing somebody wrote by accident and a type named after
    /// a package is not.
    public Optional<TypeInfo> lookup(String name) {
        return project(name).or(() -> vendored(name)).or(() -> platform(name)).or(() -> grouping(name));
    }

    /// A module, or a package, wherever it is: the project's, a dependency's, or
    /// the JDK's, in the order everything else is looked for.
    ///
    /// A package has no class file — the JDK ships none, and a project only has
    /// one where somebody wrote `package-info.java` — so this cannot go through
    /// the class-file path the way a type does. What a package *is* is its name,
    /// what it holds, and what `package-info.java` says about it.
    private Optional<TypeInfo> grouping(String name) {
        if (name.isEmpty() || name.contains("$")) return Optional.empty();
        return module(name)
                .or(() -> store.isEmpty() ? projectPackage(name) : Optional.empty())
                .or(() -> grouped("vendor", "vendor", vendor.stamp(), name, () -> vendoredPackage(name)))
                .or(() -> grouped("platform", System.getProperty("java.home"), Runtime.version().toString(), name,
                        () -> platformPackage(name)));
    }

    /// Remembered like anything else, so the second question about a package is
    /// answered from the index rather than by walking a module again.
    private Optional<TypeInfo> grouped(String kind, String location, String stamp, String name,
            Supplier<Optional<TypeInfo>> build) {
        var origin = origin(kind, location, stamp);
        if (origin.isPresent() && origin.get().fresh()) {
            var kept = kept(origin.get().id(), name);
            if (kept.isPresent()) return kept;
        }
        var built = build.get();
        built.ifPresent(group -> origin.ifPresent(where -> remember(where.id(), Map.of(name, group), false)));
        return built;
    }

    /// A module holds what it exports to everybody.
    ///
    /// It also holds `jdk.internal.*` and `sun.*`, which are the ones a reader
    /// cannot use and did not ask about: `java.base` is 196 packages, of which
    /// 116 are exported and 58 exported to anyone at all. The other 58 are
    /// qualified — `exports jdk.internal.access to java.desktop` — and a
    /// package a named module was let into is not a package this reader can
    /// import. `module-info.class` says which is which, so the answer is the
    /// module's own declaration rather than a guess about what names look
    /// internal.
    private Optional<TypeInfo> module(String name) {
        return grouped("platform", System.getProperty("java.home"), Runtime.version().toString(), name, () -> {
            var directory = modules().resolve(name);
            if (!Files.isDirectory(directory)) return Optional.empty();
            var exported = exports(directory);
            return Optional.of(group(name, TypeInfo.Kind.MODULE,
                    exported.isEmpty() ? packagesIn(directory) : exported,
                    jdkSource(name, "module-info.java")));
        });
    }

    private static List<String> exports(Path module) {
        var declaration = module.resolve("module-info.class");
        if (!Files.isRegularFile(declaration)) return List.of();
        try {
            return ClassFile.of().parse(read(declaration))
                    .findAttribute(Attributes.module())
                    .map(attribute -> attribute.exports().stream()
                            .filter(export -> export.exportsTo().isEmpty())
                            .map(export -> export.exportedPackage().name().stringValue().replace('/', '.'))
                            .sorted()
                            .toList())
                    .orElse(List.of());
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }

    /// Every package the project's own types are written in, worked out while
    /// they are all in hand.
    ///
    /// This runs where the modifiers are known, which is what lets a package
    /// list what somebody could use rather than everything javac produced. It
    /// is also why packages are indexed with the types rather than found later:
    /// a complete origin that knew the types and not their packages would
    /// answer *no such package* for one of its own.
    private Map<String, TypeInfo> packagesOf(Map<String, TypeInfo> types) {
        var visible = types.entrySet().stream()
                .filter(type -> !type.getKey().contains("$"))
                .filter(type -> type.getValue().modifiers().contains("public"))
                .map(Map.Entry::getKey)
                .toList();
        var packages = new LinkedHashMap<String, TypeInfo>();
        for (var name : visible.stream().map(Index::packageOf).filter(each -> !each.isEmpty()).distinct().toList()) {
            var contents = contents(name, visible);
            if (contents.isEmpty()) continue;
            packages.put(name, group(name, TypeInfo.Kind.PACKAGE, contents, packageInfo(name)));
        }
        return packages;
    }

    /// The same answer for a project with nowhere to keep an index: worked out
    /// from the compiled classes rather than read back from a row.
    ///
    /// Only for that case. Where there is an index, the project's packages were
    /// written with its types and a lookup has already read them — and asking
    /// javac here would undo the thing a complete origin buys, which is
    /// answering *no such name* without compiling anything.
    private Optional<TypeInfo> projectPackage(String name) {
        var visible = compile().entrySet().stream()
                .filter(type -> !type.getKey().contains("$"))
                .filter(type -> Classes.visible(type.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        var contents = contents(name, visible);
        if (contents.isEmpty()) return Optional.empty();
        return Optional.of(group(name, TypeInfo.Kind.PACKAGE, contents, packageInfo(name)));
    }

    private Optional<Source> packageInfo(String name) {
        return roots.stream()
                .map(root -> root.resolve(name.replace('.', '/') + "/package-info.java"))
                .filter(Files::isRegularFile)
                .findFirst()
                .map(path -> new Source(text(path), path.toString()));
    }

    private static String packageOf(String type) {
        var dot = type.lastIndexOf('.');
        return dot < 0 ? "" : type.substring(0, dot);
    }

    private Optional<TypeInfo> vendoredPackage(String name) {
        var contents = contents(name, vendor.types(name));
        if (contents.isEmpty()) return Optional.empty();
        return Optional.of(group(name, TypeInfo.Kind.PACKAGE, contents,
                vendor.packageInfo(name).map(found -> new Source(found.text(), found.location()))));
    }

    private Optional<TypeInfo> platformPackage(String name) {
        var path = name.replace('.', '/');
        var held = new ArrayList<String>();
        var packages = new ArrayList<String>();
        try (var modules = Files.list(modules())) {
            for (var module : modules.toList()) {
                var directory = module.resolve(path);
                if (!Files.isDirectory(directory)) continue;
                held.addAll(typesIn(directory, name));
                packages.addAll(packagesIn(directory).stream().map(under -> name + "." + under).toList());
            }
        } catch (IOException unreadable) {
            return Optional.empty();
        }
        if (held.isEmpty() && packages.isEmpty()) return Optional.empty();
        var contents = new ArrayList<>(new TreeSet<>(packages));
        contents.addAll(new TreeSet<>(held));
        return Optional.of(group(name, TypeInfo.Kind.PACKAGE, List.copyOf(contents),
                jdkSource(moduleOf(path).orElse(""), path + "/package-info.java")));
    }

    /// Subpackages first, then the types, because a reader scanning a package
    /// is either going deeper or stopping here, and the two questions do not
    /// interleave.
    private static List<String> contents(String name, List<String> types) {
        var prefix = name + ".";
        var held = new TreeSet<String>();
        var packages = new TreeSet<String>();
        for (var type : types) {
            if (!type.startsWith(prefix)) continue;
            var rest = type.substring(prefix.length());
            var dot = rest.indexOf('.');
            if (dot < 0) held.add(type);
            else packages.add(prefix + rest.substring(0, dot));
        }
        if (held.isEmpty() && packages.isEmpty()) return List.of();
        var contents = new ArrayList<>(packages);
        contents.addAll(held);
        return List.copyOf(contents);
    }

    /// A package or a module as a symbol: a name, what it holds, and whatever
    /// its `-info` file says about it.
    private static TypeInfo group(String name, TypeInfo.Kind kind, List<String> contents, Optional<Source> source) {
        var bare = new TypeInfo(name, kind, List.of(), List.of(), "", List.of(), List.of(), contents,
                List.of(), List.of(), "", List.of(), source.map(Source::location).orElse(""), 0);
        return source
                .map(found -> {
                    var comment = Javadoc.file(found.text(), kind == TypeInfo.Kind.MODULE
                            ? "module-info.java"
                            : "package-info.java");
                    return bare.documented(comment.doc(), comment.tags(), List.of(), List.of(), comment.line());
                })
                .orElse(bare);
    }

    /// The source of a symbol and where it was found.
    private record Source(String text, String location) {}

    private static List<String> packagesIn(Path directory) {
        var packages = new TreeSet<String>();
        try (var tree = Files.walk(directory)) {
            tree.filter(path -> path.toString().endsWith(".class"))
                    .map(path -> directory.relativize(path).getParent())
                    .filter(parent -> parent != null)
                    .forEach(parent -> packages.add(parent.toString().replace('/', '.')));
        } catch (IOException unreadable) {
            return List.of();
        }
        return List.copyOf(packages);
    }

    /// The types written in one directory of one module — top-level only, and
    /// without the `-info` files, which are the package speaking rather than a
    /// type in it.
    private static List<String> typesIn(Path directory, String name) {
        var types = new ArrayList<String>();
        try (var entries = Files.list(directory)) {
            for (var entry : entries.filter(path -> path.toString().endsWith(".class")).toList()) {
                var simple = entry.getFileName().toString();
                simple = simple.substring(0, simple.length() - ".class".length());
                if (simple.contains("$") || simple.endsWith("-info")) continue;
                if (!Classes.visible(read(entry))) continue;
                types.add(name + "." + simple);
            }
        } catch (IOException unreadable) {
            return List.of();
        }
        return types;
    }

    private static Optional<String> moduleOf(String path) {
        try (var modules = Files.list(modules())) {
            return modules.filter(module -> Files.isDirectory(module.resolve(path)))
                    .findFirst()
                    .map(module -> module.getFileName().toString());
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    private static Path modules() {
        return FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules");
    }

    private static Optional<Source> jdkSource(String module, String entry) {
        if (module.isEmpty()) return Optional.empty();
        return Archives.jdkSources()
                .map(zip -> zip.getPath(module + "/" + entry))
                .filter(Files::isRegularFile)
                .map(path -> new Source(text(path), jdkLocation(module, entry)));
    }

    private static String jdkLocation(String module, String entry) {
        return Path.of(System.getProperty("java.home"), "lib", "src.zip") + "!/" + module + "/" + entry;
    }

    /// What there is, before anything has been named.
    ///
    /// Every other question this index answers starts with a symbol somebody
    /// already knew about. This is the one that does not: a reader arriving
    /// with nothing asks what exists, and until now there was no way to say —
    /// `tuul docs java.util` worked and `tuul docs` was an error.
    ///
    /// Three roots, because they are three different kinds of thing and a
    /// reader holds them apart: what this project is, what it was built
    /// against, and what the language brings. Each entry is a name that can be
    /// handed straight back to [#lookup], so a listing is navigable rather than
    /// decorative.
    ///
    /// A dependency contributes its packages rather than itself, because a jar
    /// is a file and not a symbol; an empty root is left out, since a project
    /// with no dependencies should not be told it has a dependencies section.
    public List<Catalog.Root> roots() {
        if (groups == null) {
            var found = new ArrayList<Catalog.Root>();
            add(found, new Catalog.Root(PROJECT, "This project", projectPackages()));
            add(found, new Catalog.Root(DEPENDENCIES, "Dependencies", vendor.packages()));
            add(found, new Catalog.Root(PLATFORM, "The JDK", platformModules()));
            groups = List.copyOf(found);
        }
        return groups;
    }

    private static void add(List<Catalog.Root> roots, Catalog.Root root) {
        if (!root.contents().isEmpty()) roots.add(root);
    }

    /// The packages the project's own types are written in.
    ///
    /// Read back from the index where there is one, because they were written
    /// there beside the types and reading a row beats compiling a source tree.
    /// Where there is none, they are worked out from the names — which is the
    /// same answer by the longer road.
    private List<String> projectPackages() {
        var origin = origin("project", "sources", stamp);
        if (origin.isPresent() && origin.get().fresh() && origin.get().complete()) {
            try {
                var kept = store.orElseThrow().names(origin.get().id(), TypeInfo.Kind.PACKAGE);
                if (!kept.isEmpty()) return kept;
            } catch (SqliteException unavailable) {
                // work it out instead
            }
        }
        return names().stream()
                .filter(name -> !name.contains("$"))
                .map(Index::packageOf)
                .filter(name -> !name.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    /// The modules that export something to everybody.
    ///
    /// The JDK ships around ninety, and the ones exporting nothing unqualified
    /// — `jdk.internal.*` and its like — are exactly the ones a reader cannot
    /// use. Listing them would make the first thing anybody sees mostly noise.
    private static List<String> platformModules() {
        try (var modules = Files.list(modules())) {
            return modules
                    .filter(module -> !exports(module).isEmpty())
                    .map(module -> module.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    /// Every type name known from source. Vendored and JDK types are found on
    /// demand rather than enumerated.
    public List<String> names() {
        var origin = origin("project", "sources", stamp);
        if (origin.isPresent() && origin.get().fresh() && origin.get().complete()) {
            try {
                return store.orElseThrow().names(origin.get().id());
            } catch (SqliteException unavailable) {
                // ask javac instead
            }
        }
        indexProject();
        return List.copyOf(compile().keySet());
    }

    /// The symbols whose name or documentation match, best first. Only what has
    /// been indexed can be found, so the project is indexed first — the JDK and
    /// the jars turn up as they are asked about.
    public List<Catalog.Match> search(String text, int limit) {
        var kept = store.orElseThrow(() -> new IllegalStateException("there is no index to search"));
        indexProject();
        return kept.search(text, limit);
    }

    @Override
    public void close() {
        store.ifPresent(IndexStore::close);
    }

    private Optional<TypeInfo> project(String name) {
        var origin = origin("project", "sources", stamp);
        if (origin.isPresent() && origin.get().fresh()) {
            var kept = kept(origin.get().id(), name);
            if (kept.isPresent()) return kept;
            if (origin.get().complete()) return Optional.empty();
        }
        indexProject();
        return origin.isPresent()
                ? kept(origin.get().id(), name)
                : built(name, this::compiled).map(Found::type);
    }

    private Optional<TypeInfo> vendored(String name) {
        return remembered("vendor", "vendor", vendor.stamp(), name, this::vendoredOrigin);
    }

    /// The JDK is stamped with its own version. It is the one origin that never
    /// changes without being replaced outright.
    private Optional<TypeInfo> platform(String name) {
        return remembered("platform", System.getProperty("java.home"), Runtime.version().toString(), name,
                Index::platformOrigin);
    }

    /// The dependencies and the JDK are indexed a type at a time: there is no
    /// point enumerating a jar nobody asked about. So a miss here means *not
    /// indexed yet* rather than *not there*, and falls through to the work.
    private Optional<TypeInfo> remembered(String kind, String location, String stamp, String name, SymbolOrigin origins) {
        var origin = origin(kind, location, stamp);
        if (origin.isPresent() && origin.get().fresh()) {
            var kept = kept(origin.get().id(), name);
            if (kept.isPresent()) return kept;
        }
        var built = built(name, origins);
        built.ifPresent(found -> origin.ifPresent(where ->
                remember(where.id(), Map.of(found.name(), found.type()), false)));
        return built.map(Found::type);
    }

    /// A type and the binary name it was found under, which is what it gets
    /// filed as.
    private record Found(String name, TypeInfo type) {}

    private Optional<Found> built(String name, SymbolOrigin origins) {
        for (var candidate : candidates(name)) {
            var found = origins.lookup(candidate);
            if (found.isEmpty()) continue;
            return found.map(origin ->
                    new Found(candidate, document(Classes.inspect(origin.classFile()), candidate, origin)));
        }
        return Optional.empty();
    }

    private Optional<TypeInfo> kept(long origin, String name) {
        try {
            for (var candidate : candidates(name)) {
                var kept = store.orElseThrow().type(origin, candidate);
                if (kept.isPresent()) return kept;
            }
        } catch (SqliteException unavailable) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /// The index is an optimisation, never a gate. An index that is busy —
    /// another tuul is writing it — or that cannot be read at all means the
    /// answer comes the slow way, not that there is no answer.
    private Optional<IndexStore.Snapshot> origin(String kind, String location, String stamp) {
        try {
            return store.map(kept -> kept.origin(kind, location, stamp));
        } catch (SqliteException unavailable) {
            return Optional.empty();
        }
    }

    /// Writing what was learned is worth doing and not worth failing over.
    private void remember(long origin, Map<String, TypeInfo> types, boolean complete) {
        try {
            store.orElseThrow().write(origin, types, complete);
        } catch (SqliteException unavailable) {
            // the next run will learn it again
        }
    }

    /// Compiles the project once and writes down everything it holds. Doing the
    /// whole tree at once is what earns the right to say a name is *not* there
    /// without compiling again.
    private void indexProject() {
        var origin = origin("project", "sources", stamp);
        if (origin.isEmpty()) return;
        if (origin.get().fresh() && origin.get().complete()) return;

        var comments = new HashMap<String, Map<String, Javadoc.Comment>>();
        var types = new LinkedHashMap<String, TypeInfo>();
        compile().forEach((name, bytes) -> types.put(name, documented(Classes.inspect(bytes), name, comments)));
        types.putAll(packagesOf(types));
        remember(origin.get().id(), types, true);
    }

    /// One source file holds a type and everything nested in it, so its
    /// comments are read once and shared between them.
    private TypeInfo documented(TypeInfo type, String name, Map<String, Map<String, Javadoc.Comment>> comments) {
        var file = sourceFile(name);
        var found = roots.stream().map(root -> root.resolve(file)).filter(Files::isRegularFile).findFirst();
        var located = type.at(found.map(Path::toString).orElse(""));
        var read = comments.computeIfAbsent(file, path ->
                found.map(source -> Javadoc.of(text(source), file(name))).orElse(Map.of()));
        return read.isEmpty() ? located : Javadoc.attach(located, read, path(name));
    }

    private Map<String, byte[]> compile() {
        if (classes != null) return classes;
        try {
            classes = Sources.compile(roots, vendor.classpath(), compiler);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return classes;
    }

    private Optional<Origin> compiled(String name) {
        var bytes = compile().get(name);
        if (bytes == null) return Optional.empty();
        var source = roots.stream()
                .map(root -> root.resolve(sourceFile(name)))
                .filter(Files::isRegularFile)
                .findFirst()
                .map(Index::text);
        return Optional.of(new Origin(bytes, source));
    }

    private Optional<Origin> vendoredOrigin(String name) {
        return vendor.lookup(name, sourceFile(name));
    }

    /// `/modules/java.base/java/lang/String.class` names the module, and the
    /// module names the directory inside `src.zip`.
    private static Optional<Origin> platformOrigin(String name) {
        var classFile = name.replace('.', '/') + ".class";
        try (var modules = Files.list(FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules"))) {
            return modules
                    .map(module -> module.resolve(classFile))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .map(path -> {
                        var module = path.getName(1).toString();
                        return new Origin(read(path), jdk(module, name),
                                Archives.jdkSources().isPresent() ? jdkLocation(module, sourceFile(name)) : "");
                    });
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> jdk(String module, String name) {
        return Archives.jdkSources()
                .map(zip -> zip.getPath(module + "/" + sourceFile(name)))
                .filter(Files::isRegularFile)
                .map(Index::text);
    }

    private static TypeInfo document(TypeInfo type, String name, Origin origin) {
        var located = type.at(origin.location());
        return origin.source()
                .map(text -> Javadoc.attach(located, Javadoc.of(text, file(name)), path(name)))
                .orElse(located);
    }

    /// What the project looked like when it was indexed: every source file's
    /// path, size and modification time, and the jars it is compiled against.
    /// Anything that would change what javac produces changes this.
    private static String stamp(List<Path> roots, Vendor vendor) throws IOException {
        var digest = new StringBuilder();
        for (var root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var tree = Files.walk(root)) {
                for (var path : tree.filter(file -> file.toString().endsWith(".java")).sorted().toList()) {
                    digest.append(path).append(':').append(Files.size(path)).append(':')
                            .append(Files.getLastModifiedTime(path).toMillis()).append('\n');
                }
            }
        }
        digest.append(vendor.stamp());
        return fingerprint(digest.toString());
    }

    private static String fingerprint(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException("no SHA-256 in this runtime", e);
        }
    }

    /// `a.b.C.D` could be a nested type, so try turning each dot into a `$`
    /// from the right.
    private static List<String> candidates(String name) {
        var candidates = new ArrayList<String>();
        candidates.add(name);
        var binary = name.toCharArray();
        for (var i = binary.length - 1; i >= 0; i--) {
            if (binary[i] != '.') continue;
            binary[i] = '$';
            candidates.add(new String(binary));
        }
        return candidates;
    }

    /// The file that holds the type: `a.b.Outer$Inner` lives in `a/b/Outer.java`.
    private static String sourceFile(String name) {
        return outer(name).replace('.', '/') + ".java";
    }

    private static String outer(String name) {
        var nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }

    private static String file(String name) {
        var outer = outer(name);
        return outer.substring(outer.lastIndexOf('.') + 1) + ".java";
    }

    /// The type as it is written inside its file: `TypeInfo.Kind`.
    private static String path(String name) {
        return name.substring(name.lastIndexOf('.') + 1).replace('$', '.');
    }

    private static String text(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
