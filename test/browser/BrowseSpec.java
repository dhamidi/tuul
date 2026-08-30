package browser;

import harness.Check;
import harness.Checkout;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import symbols.Index;
import web.hyperspec.Hyperspec;
import web.hyperspec.Outcome;
import web.serve.Http;

/// Runs every hyperspec in `spec/browse` against a live browser.
///
/// The specs are files rather than string literals so that somebody can change
/// one without recompiling anything — a spec is a description of the
/// application, and needing a build to edit a description makes people stop
/// writing them.
///
/// They are loaded as resources rather than read from `spec/browse` directly,
/// because a path only resolves when the process was started from the right
/// directory, and a resource resolves wherever it is. Listing a resource
/// directory is not portable, so the names are written down here — and
/// [#named] checks that list against the directory, so a spec that is added
/// and forgotten fails the suite instead of quietly never running.
///
/// The index is tuul's own source, because that is what `tuul browse` shows and
/// because the interesting failures only appear in an index with enough in it
/// to rank badly. It is built into a temporary file: a spec run must never
/// write over the index the project is using.
public final class BrowseSpec {

    /// Where a person looks for the specs, and where the build copies them
    /// from.
    public static final Path SPECS = Checkout.at("spec", "browse");

    /// The specs, in the order they read: what the application is for, then
    /// how it is put together, then what it answers, then where its results
    /// lead, then its edges, then two people at once.
    public static final List<String> NAMES =
            List.of("journey", "documents", "document-search", "frames", "results", "members", "edges",
                    "together", "tree");

    private static final String SUFFIX = ".hyperspec";

    private static final String RESOURCES = "/spec/browse/";

    private BrowseSpec() {}

    /// One spec and what running it found.
    public record Result(String name, Outcome outcome) {}

    /// Every spec, against one server started for the purpose.
    ///
    /// One server for all of them rather than one each: the application holds
    /// no state between requests, and starting it five times would only make
    /// the suite slower at proving that.
    public static List<Result> all() throws Exception {
        var results = new ArrayList<Result>();
        var index = Files.createTempDirectory("tuul-browse-spec");
        index.toFile().deleteOnExit();
        try (var symbols = Index.of(List.of(Checkout.at("src")), List.of(), index.resolve("index.db"));
                var browser = Browser.of(symbols, null);
                var server = Http.start(browser.handler(), 0)) {
            var service = URI.create("http://localhost:" + server.port());
            for (var name : NAMES) {
                results.add(new Result(name,
                        Hyperspec.run(BrowseSpec.class, RESOURCES + name + SUFFIX, service)));
            }
        }
        return List.copyOf(results);
    }

    /// Prints what every spec found and answers with the number that failed, so
    /// a command can exit on it.
    ///
    /// A spec that fails is reported, not thrown: the point of running five of
    /// them is to learn about all five, and the first failure is rarely the
    /// most informative one.
    public static int report(Writer out) throws Exception {
        var failed = 0;
        var forgotten = forgotten();
        if (!forgotten.isEmpty()) {
            out.write("FAIL " + SPECS + " holds " + forgotten + ", which no runner names — add them to BrowseSpec.NAMES\n\n");
            failed++;
        }
        for (var result : all()) {
            out.write(result.name() + SUFFIX + "\n");
            for (var check : result.outcome().checks()) {
                out.write("  ok   " + check.client() + " " + check.what() + "\n");
            }
            for (var failure : result.outcome().failures()) out.write("  FAIL " + failure.describe() + "\n");
            out.write("  " + result.outcome().passed() + " met"
                    + (result.outcome().ok() ? "" : ", " + result.outcome().failures().size() + " not") + "\n\n");
            if (!result.outcome().ok()) failed++;
        }
        out.flush();
        return failed;
    }

    /// The suite as ordinary checks.
    public static void run() throws Exception {
        Check.equal("every spec in " + SPECS + " is one the runner names", List.of(), forgotten());
        for (var result : all()) {
            Check.that(result.name() + " holds: " + result.outcome().report(), result.outcome().ok());
        }
    }

    /// Specs sitting in the directory that no runner names.
    ///
    /// The run itself does not read the directory — it cannot, portably, since
    /// a resource directory has no listing — so this is what stops a spec from
    /// being written, committed and never run. It only asks when the directory
    /// is there, which is during development and not from inside a jar.
    public static List<String> forgotten() throws IOException {
        if (!Files.isDirectory(SPECS)) return List.of();
        try (var files = Files.list(SPECS)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(SUFFIX))
                    .map(name -> name.substring(0, name.length() - SUFFIX.length()))
                    .filter(name -> !NAMES.contains(name))
                    .sorted()
                    .toList();
        }
    }
}
