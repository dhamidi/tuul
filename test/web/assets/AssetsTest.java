package web.assets;

import harness.Check;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class AssetsTest {

    private AssetsTest() {}

    public static void run() throws IOException {
        var root = tree();
        var assets = Assets.of(root);

        digests(root, assets);
        manifests(assets);
        stylesheets(assets);
        importmaps(assets);
        serves(assets);
        missing(assets);
        streams(assets);
        overrides(root);
        hotwired();
    }

    private static void digests(Path root, Assets assets) throws IOException {
        var logo = assets.find("images/logo.png").orElseThrow();
        Check.equal("a digest is sixteen hex characters", 16, logo.digest().length());
        Check.that("and it is hex", logo.digest().matches("[0-9a-f]+"));
        Check.equal("the digest goes before the extension",
                "images/logo-" + logo.digest() + ".png",
                logo.digested());
        Check.equal("reading the same tree twice gives the same digest",
                logo.digest(),
                Assets.of(root).find("images/logo.png").orElseThrow().digest());

        var before = assets.find("app.css").orElseThrow().digest();
        Check.that("a stylesheet's digest is not its file's",
                !before.equals(digestOf(Files.readAllBytes(root.resolve("app.css")))));

        Files.writeString(root.resolve("images/logo.png"), "different pixels");
        var after = Assets.of(root);
        Check.that("changing an asset changes its digest",
                !after.find("images/logo.png").orElseThrow().digest().equals(logo.digest()));
        Check.that("and changes the digest of the stylesheet that references it",
                !after.find("app.css").orElseThrow().digest().equals(before));
    }

    private static void manifests(Assets assets) {
        var manifest = assets.manifest();
        Check.equal("the manifest maps logical names to digested ones",
                assets.find("app.css").orElseThrow().digested(),
                manifest.string("app.css", ""));
        Check.equal("it holds every asset found", assets.names().size(), manifest.fields().size());
        Check.equal("a URL is the mount prefix and the digested name",
                "/assets/" + assets.find("application.js").orElseThrow().digested(),
                assets.url("application.js"));
        Check.throwing("asking for one that is not there fails", () -> assets.url("nothing.js"));
    }

    private static void stylesheets(Assets assets) {
        var css = assets.find("app.css").orElseThrow().compiled().orElseThrow();
        Check.that("a reference becomes the digested URL",
                css.contains("url(\"/assets/images/logo-" + assets.find("images/logo.png").orElseThrow().digest() + ".png\")"));
        Check.that("an absolute URL is left alone", css.contains("url(https://example.com/far.png)"));
        Check.that("a data URI is left alone", css.contains("url(data:image/gif;base64,R0lGOD)"));
        Check.that("a protocol-relative URL is left alone", css.contains("url(//cdn.example.com/x.png)"));
        Check.that("a site-absolute path is left alone", css.contains("url(/elsewhere/y.png)"));
        Check.that("a reference to nothing this pipeline has is left alone", css.contains("url(missing.png)"));

        var deep = assets.find("nested/deep.css").orElseThrow().compiled().orElseThrow();
        Check.that("a reference climbs out of its own directory",
                deep.contains("url(\"/assets/images/logo-" + assets.find("images/logo.png").orElseThrow().digest() + ".png\")"));
        Check.that("a fragment survives, because it picks the icon out of the sprite",
                deep.contains("-" + assets.find("icons.svg").orElseThrow().digest() + ".svg#home\")"));
        Check.that("a query survives too", deep.contains(".woff2?v=2\")"));
        Check.that("an @import is a reference like any other",
                deep.contains("@import \"/assets/app-" + assets.find("app.css").orElseThrow().digest() + ".css\""));

        var cyclic = assets.find("loop-a.css").orElseThrow();
        Check.that("a cycle of imports still resolves rather than hanging", !cyclic.digest().isEmpty());
    }

    private static void importmaps(Assets assets) throws IOException {
        var map = Importmap.of().pin("application", "application.js");
        Check.equal("a pin becomes a bare specifier and a digested URL",
                "{\"imports\":{\"application\":\"/assets/" + assets.find("application.js").orElseThrow().digested() + "\"}}",
                map.json(assets).text());
        Check.equal("pins keep the order they were made in",
                List.of("a", "b"),
                List.copyOf(Importmap.of().pin("a", "application.js").pin("b", "application.js").pins().keySet()));
        Check.throwing("pinning something that is not there fails when the map is written",
                () -> Importmap.of().pin("gone", "gone.js").json(assets));

        var out = new StringWriter();
        map.write(assets, out);
        Check.that("the map is written as a script element", out.toString().startsWith("<script type=\"importmap\">"));
        Check.that("and every module is preloaded", out.toString().contains("<link rel=\"modulepreload\""));
        var risky = new StringWriter();
        Importmap.of().pin("</script><b>", "application.js").write(assets, risky);
        Check.that("angle brackets in the map are escaped, since </script> ends the element wherever it appears",
                inline(risky.toString()).contains("\\u003c/script\\u003e") && !inline(risky.toString()).contains("<"));
    }

    private static void serves(Assets assets) {
        var asset = assets.find("app.css").orElseThrow();
        var digested = assets.serve("/assets/" + asset.digested());
        Check.equal("a digested name is served", 200, digested.status());
        Check.equal("as what it is", "text/css; charset=utf-8", digested.headers().get("Content-Type"));
        Check.equal("cached until the heat death of the universe",
                "public, max-age=31536000, immutable",
                digested.headers().get("Cache-Control"));
        Check.equal("with the digest as its ETag", "\"" + asset.digest() + "\"", digested.headers().get("ETag"));
        Check.equal("and a length", String.valueOf(asset.length()), digested.headers().get("Content-Length"));

        var plain = assets.serve("app.css");
        Check.equal("a plain name is served too", 200, plain.status());
        Check.equal("but has to be asked about again, since that name will mean something else tomorrow",
                "public, max-age=0, must-revalidate",
                plain.headers().get("Cache-Control"));

        var again = assets.serve("/assets/" + asset.digested(), "\"" + asset.digest() + "\"");
        Check.equal("an unchanged asset is not sent twice", 304, again.status());
        Check.that("and 304 has no body", again.body().isEmpty());
        Check.that("but keeps the ETag", again.headers().containsKey("ETag"));

        Check.equal("the mount prefix is optional", 200, assets.serve(asset.digested()).status());
        Check.equal("as is a leading slash", 200, assets.serve("/app.css").status());
    }

    private static void missing(Assets assets) {
        Check.equal("an asset nobody has is a 404", 404, assets.serve("/assets/nothing.css").status());
        Check.that("with no body", assets.serve("/assets/nothing.css").body().isEmpty());
        Check.equal("a name that only looks digested is not one",
                404,
                assets.serve("/assets/app-0000000000000000.css").status());
        Check.equal("nothing escapes the load path, because a name is looked up rather than resolved",
                404,
                assets.serve("/assets/../../../etc/passwd").status());
        Check.equal("not even spelled the long way", 404, assets.serve("../../etc/passwd").status());
    }

    /// A pipeline that reads a file into memory to serve it has decided how big
    /// an asset is allowed to be. This one has not.
    private static void streams(Assets assets) throws IOException {
        var chunks = new Chunks();
        assets.find("big.bin").orElseThrow().writeTo(chunks);
        Check.equal("all of it arrives", 1_048_576L, chunks.total);
        Check.that("in more than one piece", chunks.sizes.size() > 1);
        Check.that("none of them large: " + chunks.largest(), chunks.largest() <= 64 * 1024);

        var bytes = new ByteArrayOutputStream();
        assets.find("app.css").orElseThrow().writeTo(bytes);
        Check.equal("a rewritten stylesheet is served as rewritten, not as it lies on disk",
                assets.find("app.css").orElseThrow().compiled().orElseThrow(),
                bytes.toString(StandardCharsets.UTF_8));
    }

    private static void overrides(Path root) throws IOException {
        var mine = Files.createTempDirectory("tuul-assets-mine");
        mine.toFile().deleteOnExit();
        Files.writeString(mine.resolve("application.js"), "// mine\n");

        var assets = Assets.of(List.of(mine, root));
        Check.equal("the first load path wins", mine.resolve("application.js"), assets.find("application.js").orElseThrow().file());
        Check.that("and everything else is still found", assets.find("app.css").isPresent());
    }

    private static void hotwired() {
        var assets = Assets.standard(List.of());
        Check.that("Turbo is on the standard load path", assets.find(Hotwired.TURBO_FILE).isPresent());
        Check.that("so is Stimulus", assets.find(Hotwired.STIMULUS_FILE).isPresent());
        Check.that("and the standard import map pins them both",
                Importmap.standard().json(assets).text().contains(Hotwired.TURBO)
                        && Importmap.standard().json(assets).text().contains(Hotwired.STIMULUS));
        Check.that("Turbo is served as a module, or a browser will refuse it",
                assets.serve(assets.find(Hotwired.TURBO_FILE).orElseThrow().digested())
                        .headers()
                        .get("Content-Type")
                        .startsWith("text/javascript"));
    }

    /// What sits inside the importmap script element, which is the only part
    /// that has to survive being read as HTML.
    private static String inline(String tags) {
        return tags.substring(tags.indexOf('>') + 1, tags.indexOf("</script>"));
    }

    private static String digestOf(byte[] content) {
        return sha(content);
    }

    private static String sha(byte[] content) {
        try {
            var sha = java.security.MessageDigest.getInstance("SHA-256").digest(content);
            var hex = new StringBuilder();
            for (var i = 0; i < 8; i++) hex.append("%02x".formatted(sha[i]));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path tree() throws IOException {
        var root = Files.createTempDirectory("tuul-assets");
        root.toFile().deleteOnExit();
        Files.createDirectories(root.resolve("images"));
        Files.createDirectories(root.resolve("nested"));

        Files.writeString(root.resolve("images/logo.png"), "pretend this is a png");
        Files.writeString(root.resolve("icons.svg"), "<svg><symbol id=\"home\"/></svg>");
        Files.writeString(root.resolve("application.js"), "import \"@hotwired/turbo\";\n");
        Files.write(root.resolve("big.bin"), new byte[1_048_576]);
        Files.writeString(root.resolve("fonts.woff2"), "not really a font");
        Files.writeString(root.resolve("app.css"), """
                body {
                  background: url("images/logo.png");
                  border-image: url(https://example.com/far.png);
                  list-style: url(data:image/gif;base64,R0lGOD);
                  cursor: url(//cdn.example.com/x.png);
                  content: url(/elsewhere/y.png);
                  mask: url(missing.png);
                }
                """);
        Files.writeString(root.resolve("nested/deep.css"), """
                @import "../app.css";
                .icon { background: url("../images/logo.png"); }
                .home { background: url('../icons.svg#home'); }
                @font-face { src: url("../fonts.woff2?v=2"); }
                """);
        Files.writeString(root.resolve("loop-a.css"), "@import \"loop-b.css\";\n");
        Files.writeString(root.resolve("loop-b.css"), "@import \"loop-a.css\";\n");
        return root;
    }

    /// An output stream that remembers how it was written to, which is the only
    /// way to tell streaming from a large array.
    private static final class Chunks extends OutputStream {

        private final List<Integer> sizes = new ArrayList<>();
        private long total;

        @Override
        public void write(int b) {
            sizes.add(1);
            total++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            sizes.add(length);
            total += length;
        }

        private int largest() {
            return sizes.stream().mapToInt(Integer::intValue).max().orElse(0);
        }
    }
}
