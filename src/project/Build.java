package project;

import compiler.ClassSink;
import compiler.Compiler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import modules.ModuleGraph;
import symbols.Vendor;

/// Compiles every project source root as its declared named module.
///
/// Application, entrypoint, task, and test modules are separate outputs below
/// `build/modules`. Dependencies are always module-path inputs. A missing
/// descriptor is an error; there is no unnamed-module project fallback.
public final class Build {

    public record Result(int classes, List<String> problems) {
        public boolean ok() { return problems.isEmpty(); }
    }

    private Build() {}

    public static Result compile(Layout layout) throws IOException {
        return compile(layout, Compiler.system());
    }

    public static Result compile(Layout layout, Compiler compiler) throws IOException {
        if (!layout.exists()) return new Result(0, List.of("no src/ in " + layout.root().toAbsolutePath()));
        var vendor = Vendor.of(List.of(layout.vendor()));
        var vendorModules = modulePath(vendor);
        var application = layout.application();
        var expected = new java.util.LinkedHashSet<String>();
        expected.add(application.name());
        var preserved = declaredModules(layout, expected);
        var total = compile(layout, application, vendorModules, compiler);
        if (!total.ok()) return total;
        var entrypoint = layout.entrypointModule();
        if (entrypoint.isPresent()) {
            var path = new ArrayList<>(vendorModules);
            path.add(layout.moduleOutput(application.name()));
            var built = compile(layout, entrypoint.get(), path, compiler);
            if (!built.ok()) return new Result(total.classes(), built.problems());
            total = new Result(total.classes() + built.classes(), List.of());
            expected.add(entrypoint.get().name());
        }
        // A task root is intentionally not part of the application build, but
        // it must still be a valid named source root when it exists.
        layout.taskModule();
        validate(layout, vendorModules, expected.stream().toList());
        removeStaleModules(layout, preserved);
        return total;
    }

    public static Result compileTests(Layout layout) throws IOException {
        return compileTests(layout, Compiler.system());
    }

    public static Result compileTests(Layout layout, Compiler compiler) throws IOException {
        var test = layout.testModule();
        if (test.isEmpty()) return new Result(0, List.of("no test/ in " + layout.root().toAbsolutePath()));
        var built = compile(layout, compiler);
        if (!built.ok()) return built;
        var vendor = Vendor.of(List.of(layout.vendor()));
        var path = new ArrayList<>(modulePath(vendor));
        path.add(layout.moduleOutput(layout.application().name()));
        layout.entrypointModule().map(module -> path.add(layout.moduleOutput(module.name())));
        var roots = new ArrayList<String>();
        roots.add(layout.application().name());
        layout.entrypointModule().ifPresent(module -> roots.add(module.name()));
        roots.add(test.get().name());
        // Repository tests deliberately retain their production packages so
        // they can exercise package-private contracts. Compile that one test
        // root as a patch of `tuul`, then launch its small named runner module.
        // Generated project tests have test-owned packages and compile as a
        // normal named module, so they never create a split package.
        if (!test.get().name().equals("tuul.test")) {
            var result = compile(layout, test.get(), path, compiler);
            if (result.ok()) {
                validate(layout, path, roots);
                removeStaleModules(layout, declaredModules(layout, roots));
            }
            return result.ok() ? new Result(built.classes() + result.classes(), List.of()) : result;
        }
        var patchSources = test.get().sources().stream()
                .filter(source -> !source.equals(test.get().descriptor()))
                .filter(source -> !source.getFileName().toString().equals("Run.java"))
                .toList();
        var patched = compilePatch(layout, test.get(), patchSources, path, compiler);
        if (!patched.ok()) return new Result(built.classes(), patched.problems());
        var runnerSources = test.get().sources().stream()
                .filter(source -> source.equals(test.get().descriptor())
                        || source.getFileName().toString().equals("Run.java"))
                .toList();
        var runner = new Layout.SourceModule(test.get().name(), test.get().root(), test.get().descriptor(), runnerSources);
        var result = compile(layout, runner, path, compiler,
                java.util.Optional.of(new Compiler.Request.Patch(layout.application().name(),
                        layout.root().resolve("build/patches").resolve(layout.application().name()))),
                patchSources.stream().map(Build::packageName).flatMap(java.util.Optional::stream)
                        .distinct().map(name -> "tuul/" + name + "=tuul.test").toList(), false);
        if (!result.ok()) return new Result(built.classes() + patched.classes(), result.problems());
        validate(layout, path, roots);
        removeStaleModules(layout, declaredModules(layout, roots));
        return new Result(built.classes() + patched.classes() + result.classes(), List.of());
    }

    private static Result compile(Layout layout, Layout.SourceModule module, List<Path> modulePath,
            Compiler compiler) throws IOException {
        return compile(layout, module, modulePath, compiler, java.util.Optional.empty(), List.of());
    }

    private static Result compile(Layout layout, Layout.SourceModule module, List<Path> modulePath,
            Compiler compiler, java.util.Optional<Compiler.Request.Patch> patch,
            List<String> addExports) throws IOException {
        return compile(layout, module, modulePath, compiler, patch, addExports, true);
    }

    private static Result compile(Layout layout, Layout.SourceModule module, List<Path> modulePath,
            Compiler compiler, java.util.Optional<Compiler.Request.Patch> patch,
            List<String> addExports, boolean copyResources) throws IOException {
        var output = layout.moduleOutput(module.name());
        var fingerprint = fingerprint(module, modulePath) + (copyResources ? "" : "-code-only");
        if (current(layout, module.name(), fingerprint, output)) {
            return new Result(written(output), List.of());
        }
        var stagingRoot = layout.root().resolve("build/.tuul");
        Files.createDirectories(stagingRoot);
        var staging = Files.createTempDirectory(stagingRoot, "module-" + module.name() + "-");
        try {
            var result = compiler.compile(new Compiler.Request(module.sources(), modulePath, module.name(),
                    Runtime.version().feature(), true, patch, addExports, java.util.Map.of()), sink(staging));
            if (!result.ok()) return new Result(0, report(result.problems()));
            if (copyResources) resources(layout, module.root(), staging);
            replace(output, staging);
            remember(layout, module.name(), fingerprint);
            return new Result(result.classes(), List.of());
        } finally {
            if (Files.exists(staging)) clear(staging);
        }
    }

    private static Result compilePatch(Layout layout, Layout.SourceModule test, List<Path> sources,
            List<Path> modulePath, Compiler compiler) throws IOException {
        if (sources.isEmpty()) return new Result(0, List.of());
        var output = layout.root().resolve("build/patches").resolve(layout.application().name());
        var fingerprint = fingerprint(test, modulePath) + "-patch-resources";
        if (current(layout, test.name() + "-patch", fingerprint, output)) return new Result(written(output), List.of());
        // javac treats module-info.java specially even when it is not an
        // explicit source argument if the patch root contains it. Stage only
        // test sources so the test root's descriptor can remain the separate
        // named runner module.
        var patchRoot = layout.root().resolve("build/.tuul/patch-sources-" + test.name());
        clear(patchRoot);
        Files.createDirectories(patchRoot);
        var staged = new ArrayList<Path>();
        for (var source : sources) {
            var target = patchRoot.resolve(test.root().relativize(source));
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            staged.add(target);
        }
        var result = compiler.compile(new Compiler.Request(staged, modulePath, layout.application().name(),
                Runtime.version().feature(), true,
                java.util.Optional.of(new Compiler.Request.Patch(layout.application().name(), patchRoot)),
                List.of(), java.util.Map.of()), sink(output));
        if (!result.ok()) return new Result(0, report(result.problems()));
        resources(layout, test.root(), output);
        remember(layout, test.name() + "-patch", fingerprint);
        return new Result(result.classes(), List.of());
    }

    private static void validate(Layout layout, List<Path> dependencies, List<String> roots) throws IOException {
        var artifacts = new ArrayList<Path>();
        artifacts.add(layout.moduleOutput(layout.application().name()));
        layout.entrypointModule().ifPresent(module -> artifacts.add(layout.moduleOutput(module.name())));
        layout.testModule().ifPresent(module -> {
            var output = layout.moduleOutput(module.name());
            if (Files.isDirectory(output)) artifacts.add(output);
        });
        artifacts.addAll(dependencies);
        ModuleGraph.resolve(artifacts.stream().filter(Files::exists).distinct().toList(), roots);
    }

    private static void removeStaleModules(Layout layout, java.util.Set<String> expected) throws IOException {
        var root = layout.root().resolve("build/modules");
        if (!Files.isDirectory(root)) return;
        try (var children = Files.list(root)) {
            for (var child : children.filter(Files::isDirectory).toList()) {
                if (!expected.contains(child.getFileName().toString())) clear(child);
            }
        }
    }

    private static java.util.Set<String> declaredModules(Layout layout,
            java.util.Collection<String> roots) throws IOException {
        var declared = new java.util.LinkedHashSet<>(roots);
        layout.entrypointModule().ifPresent(module -> declared.add(module.name()));
        layout.testModule().ifPresent(module -> declared.add(module.name()));
        layout.taskModule().ifPresent(module -> declared.add(module.name()));
        return declared;
    }

    /// ModuleGraph is the dependency authority. The path list is only the
    /// javac/java representation of the already-resolved named artifacts.
    private static List<Path> modulePath(Vendor vendor) throws IOException {
        if (vendor.artifacts().isEmpty()) return List.of();
        return vendor.graph().modules().values().stream()
                .map(ModuleGraph.Node::origin)
                .flatMap(origin -> origin.path().stream())
                .distinct().toList();
    }

    private static java.util.Optional<String> packageName(Path source) {
        try {
            var text = Files.readString(source).replaceAll("(?s)/\\*.*?\\*/", "")
                    .replaceAll("(?m)//.*$", "");
            var match = java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;").matcher(text);
            return match.find() ? java.util.Optional.of(match.group(1)) : java.util.Optional.empty();
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static ClassSink sink(Path output) {
        return name -> {
            var file = output.resolve(name.replace('.', '/') + ".class");
            Files.createDirectories(file.getParent());
            return Files.newOutputStream(file);
        };
    }

    private static String fingerprint(Layout.SourceModule module, List<Path> modulePath) throws IOException {
        var digest = sha256();
        update(digest, module.name());
        for (var source : module.sources()) file(digest, module.root().relativize(source).toString(), source);
        try (var tree = Files.walk(module.root())) {
            for (var resource : tree.filter(Files::isRegularFile)
                    .filter(path -> !path.toString().endsWith(".java")).sorted().toList()) {
                file(digest, module.root().relativize(resource).toString(), resource);
            }
        }
        for (var path : modulePath) {
            update(digest, path.toString());
            if (Files.isRegularFile(path)) file(digest, path.toString(), path);
            else if (Files.isDirectory(path)) {
                try (var tree = Files.walk(path)) {
                    for (var child : tree.filter(Files::isRegularFile).sorted().toList()) {
                        file(digest, path.relativize(child).toString(), child);
                    }
                }
            }
        }
        update(digest, String.valueOf(Runtime.version().feature()));
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void resources(Layout layout, Path root, Path output) throws IOException {
        try (var tree = Files.walk(root)) {
            for (var file : tree.filter(Files::isRegularFile)
                    .filter(path -> !path.toString().endsWith(".java"))
                    .filter(path -> !root.equals(layout.src()) || !path.startsWith(layout.resources()))
                    .sorted().toList()) {
                var destination = output.resolve(root.relativize(file).toString());
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (root.equals(layout.src()) && Files.isDirectory(layout.resources())) {
            try (var tree = Files.walk(layout.resources())) {
                for (var file : tree.filter(Files::isRegularFile).sorted().toList()) {
                    var destination = output.resolve(layout.resources().relativize(file).toString());
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static boolean current(Layout layout, String module, String fingerprint, Path output) throws IOException {
        var stamp = layout.root().resolve("build/.tuul/module-" + module + ".stamp");
        return Files.isDirectory(output) && Files.isRegularFile(stamp)
                && Files.readString(stamp).equals(fingerprint);
    }

    private static void remember(Layout layout, String module, String fingerprint) throws IOException {
        var directory = layout.root().resolve("build/.tuul");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("module-" + module + ".stamp"), fingerprint);
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void file(MessageDigest digest, String name, Path path) throws IOException {
        update(digest, name);
        try (InputStream input = Files.newInputStream(path)) {
            var buffer = new byte[8192];
            for (var read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static void clear(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var tree = Files.walk(directory)) {
            for (var path : tree.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    /// Publishes a completed module without exposing a partially written tree.
    /// The old tree is moved aside only after compilation and resource copying
    /// finish, so a self-hosted build never removes classes needed by its CLI.
    private static void replace(Path output, Path staging) throws IOException {
        Files.createDirectories(output.getParent());
        var backup = output.resolveSibling("." + output.getFileName() + "-old-"
                + Long.toUnsignedString(System.nanoTime()));
        var hadOld = Files.exists(output);
        if (hadOld) Files.move(output, backup);
        try {
            Files.move(staging, output);
        } catch (IOException failure) {
            if (hadOld && !Files.exists(output)) Files.move(backup, output);
            throw failure;
        }
        if (hadOld) clear(backup);
    }

    private static int written(Path output) throws IOException {
        try (var tree = Files.walk(output)) {
            return (int) tree.filter(path -> path.toString().endsWith(".class")).count();
        }
    }

    private static List<String> report(List<Compiler.Problem> problems) {
        return problems.stream().map(problem -> {
            var source = problem.source() == null ? "" : problem.source().getFileName() + ":" + problem.line() + " ";
            return source + problem.message();
        }).limit(20).toList();
    }
}
