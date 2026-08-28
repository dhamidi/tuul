package web.assets;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import json.Json;

/// The asset pipeline, which is deliberately small: find a file, name it after
/// its content, and serve it so it never has to be asked for again.
///
/// There is no bundler and no transpiler here, and nothing to install. That is
/// not a shortcut — a browser has been able to load ES modules for years, and
/// an import map tells it where they are, so the build step that existed to
/// undo the module system is a build step nobody needs any more.
///
/// ```
/// var assets = Assets.standard(List.of(Path.of("app/assets")));
/// assets.url("app.css");            // /assets/app-9f2b1c0d4e6a8b31.css
/// assets.serve("/assets/app-9f2b1c0d4e6a8b31.css");
/// ```
///
/// Load paths are searched in order and the first answer wins, so an
/// application overrides what tuul ships by putting a file of the same name
/// earlier in the list.
public final class Assets {

    /// Where assets are mounted unless somebody says otherwise. It is part of
    /// the pipeline rather than a parameter to it, because a stylesheet's
    /// rewritten references have to be URLs, and a URL needs to know this.
    public static final String PREFIX = "/assets";

    private static final int DIGEST = 16;

    private final List<Path> loadPaths;
    private final String prefix;
    private final Map<String, Path> files;
    private final Map<String, Asset> resolved = new ConcurrentHashMap<>();

    private Assets(List<Path> loadPaths, String prefix, Map<String, Path> files) {
        this.loadPaths = List.copyOf(loadPaths);
        this.prefix = prefix;
        this.files = Map.copyOf(files);
    }

    public static Assets of(Path... loadPaths) {
        return of(List.of(loadPaths));
    }

    public static Assets of(List<Path> loadPaths) {
        return of(loadPaths, PREFIX);
    }

    public static Assets of(List<Path> loadPaths, String prefix) {
        return new Assets(loadPaths, prefix, scan(loadPaths));
    }

    /// The application's own assets, and then Turbo and Stimulus.
    ///
    /// Hotwired is here because the framework assumes it: `web.ui` writes Turbo
    /// attributes and Stimulus actions into pages, so an application that got
    /// neither would render markup nothing acts on. It goes last, so an
    /// application that ships a file of the same name replaces it.
    ///
    /// Nothing else is here. A package that ships assets says where they are —
    /// [web.ui.Ui#assets()], [web.cable.Cable#assets()] — and an application
    /// that uses one puts it on this list beside its own, which it already does
    /// for the import map. Enumerating them instead would mean this package
    /// importing the packages built on top of it, and it would hand every
    /// application every file tuul has ever shipped.
    ///
    /// ```
    /// Assets.standard(List.of(Bundled.of(Browser.class, "assets"), Cable.assets(), Ui.assets()));
    /// ```
    public static Assets standard(List<Path> loadPaths) {
        var all = new ArrayList<>(loadPaths);
        all.add(Hotwired.path());
        return of(all);
    }

    public String prefix() {
        return prefix;
    }

    public List<Path> loadPaths() {
        return loadPaths;
    }

    /// Every logical name the load paths hold, in the order they were found.
    public Set<String> names() {
        return files.keySet();
    }

    public Optional<Asset> find(String logical) {
        if (!files.containsKey(logical)) return Optional.empty();
        return Optional.of(resolve(logical, new ArrayDeque<>()));
    }

    /// The URL an application writes into a page. It fails rather than answering
    /// with something that will 404 later, because a missing asset found while
    /// rendering is a bug, and a missing asset found by a browser is a support
    /// ticket.
    public String url(String logical) {
        var asset = find(logical).orElseThrow(() -> new AssetException(
                "no asset named " + logical + " in " + loadPaths));
        return prefix + "/" + asset.digested();
    }

    /// Logical name to digested name, for everything. This is the manifest
    /// Propshaft writes to disk; here it is answered from memory, since the
    /// pipeline is in the same process as the application.
    public Json.Object manifest() {
        var manifest = Json.Object.of();
        for (var logical : names()) manifest = manifest.with(logical, find(logical).orElseThrow().digested());
        return manifest;
    }

    public Served serve(String path) {
        return serve(path, "");
    }

    /// Answers a request for an asset. `path` may be the digested name or the
    /// plain one, with or without the mount prefix — a caller that has already
    /// stripped its own routing prefix is as welcome as one that has not.
    ///
    /// A digested name is cached forever, because the name changes when the
    /// content does and nothing else can happen. A plain name is revalidated,
    /// because that name will mean something else tomorrow.
    public Served serve(String path, String ifNoneMatch) {
        var name = requested(path);
        var digested = digested(name);
        var asset = digested.or(() -> find(name));
        if (asset.isEmpty()) return new Served(404, Map.of(), Optional.empty());

        var headers = new LinkedHashMap<String, String>();
        var etag = "\"" + asset.get().digest() + "\"";
        headers.put("ETag", etag);
        headers.put("Cache-Control", digested.isPresent()
                ? "public, max-age=31536000, immutable"
                : "public, max-age=0, must-revalidate");
        if (etag.equals(ifNoneMatch)) return new Served(304, headers, Optional.empty());

        headers.put("Content-Type", asset.get().type());
        headers.put("Content-Length", String.valueOf(asset.get().length()));
        return new Served(200, headers, asset);
    }

    /// A path with the mount prefix and any leading slash taken off. Nothing
    /// here resolves a file system path — a name is looked up in what was
    /// scanned, so `../../etc/passwd` is simply a name nobody has, and cannot
    /// be anything else.
    private String requested(String path) {
        var name = path.startsWith(prefix + "/") ? path.substring(prefix.length() + 1) : path;
        return name.startsWith("/") ? name.substring(1) : name;
    }

    /// Reads a digested name backwards: strip the digest, look up what is left,
    /// and only answer if that asset really does digest to this. A name that
    /// merely looks digested belongs to whoever it looks like.
    private Optional<Asset> digested(String name) {
        var dot = name.lastIndexOf('.');
        var stem = dot < 0 ? name : name.substring(0, dot);
        var extension = dot < 0 ? "" : name.substring(dot);
        var dash = stem.lastIndexOf('-');
        if (dash < 0 || stem.length() - dash - 1 != DIGEST) return Optional.empty();
        return find(stem.substring(0, dash) + extension).filter(asset -> asset.digested().equals(name));
    }

    /// Resolving is memoised because a stylesheet asks about every image it
    /// mentions, and a page asks about every stylesheet.
    private Asset resolve(String logical, Deque<String> compiling) {
        var known = resolved.get(logical);
        if (known != null) return known;
        var asset = compile(logical, compiling);
        resolved.putIfAbsent(logical, asset);
        return resolved.get(logical);
    }

    private Asset compile(String logical, Deque<String> compiling) {
        var file = files.get(logical);
        var type = Types.of(logical);
        if (!Types.stylesheet(logical)) return new Asset(logical, digest(file), type, file, Optional.empty());

        compiling.push(logical);
        try {
            var css = Files.readString(file);
            var rewritten = Stylesheets.rewrite(css, logical, reference -> reference(reference, compiling));
            return new Asset(logical, digest(rewritten.getBytes(StandardCharsets.UTF_8)), type, file, Optional.of(rewritten));
        } catch (IOException e) {
            throw new AssetException("cannot read " + logical, e);
        } finally {
            compiling.pop();
        }
    }

    /// The URL of one thing a stylesheet mentions.
    ///
    /// A stylesheet that imports something which imports it back cannot have a
    /// digest that depends on itself, so the reference behind the cycle is
    /// digested from the file as it lies on disk. A cycle is rare enough to
    /// break this way and strange enough that breaking it loudly would help
    /// nobody.
    private Optional<String> reference(String logical, Deque<String> compiling) {
        if (!files.containsKey(logical)) return Optional.empty();
        if (compiling.contains(logical)) return Optional.of(prefix + "/" + raw(logical).digested());
        return Optional.of(prefix + "/" + resolve(logical, compiling).digested());
    }

    private Asset raw(String logical) {
        var file = files.get(logical);
        return new Asset(logical, digest(file), Types.of(logical), file, Optional.empty());
    }

    private static Map<String, Path> scan(List<Path> loadPaths) {
        var files = new LinkedHashMap<String, Path>();
        for (var root : loadPaths) {
            if (!Files.isDirectory(root)) continue;
            try (var tree = Files.walk(root)) {
                tree.filter(Files::isRegularFile).sorted().forEach(file -> files.putIfAbsent(logical(root, file), file));
            } catch (IOException e) {
                throw new AssetException("cannot read the assets in " + root, e);
            }
        }
        return files;
    }

    /// A logical name is the path under the load path, separated by `/` whatever
    /// the file system uses — it ends up in a URL, and a URL has no backslashes.
    private static String logical(Path root, Path file) {
        var relative = root.relativize(file);
        var segments = new ArrayList<String>();
        relative.forEach(segment -> segments.add(segment.toString()));
        return String.join("/", segments);
    }

    /// The digest of a file, read as it is hashed. An asset can be a video, and
    /// nothing here needs it in memory to know what to call it.
    private static String digest(Path file) {
        var sha = sha256();
        var buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
            for (var read = in.read(buffer); read >= 0; read = in.read(buffer)) sha.update(buffer, 0, read);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return hex(sha.digest());
    }

    private static String digest(byte[] content) {
        return hex(sha256().digest(content));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssetException("this JDK has no SHA-256", e);
        }
    }

    /// Sixteen hex characters of it. A digest here has one job — to change when
    /// the content does, among the handful of files one application serves — and
    /// sixty-four bits of it is a collision nobody will see. The rest would only
    /// make the URL longer.
    private static String hex(byte[] digest) {
        var hex = new StringBuilder();
        for (var i = 0; i < DIGEST / 2; i++) hex.append("%02x".formatted(digest[i]));
        return hex.toString();
    }
}
