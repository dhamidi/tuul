import harness.Check;
import java.time.Duration;
import java.util.List;

/// Runs the fast test suites by default, all suites with `--mode all`, or one
/// suite selected by `--suite`.
///
/// The runner executes suites in declaration order. This isolates each
/// duration from other suites and makes a slow report actionable. Each suite
/// has a bounded timeout.

void main(String[] arguments) {
    var suites = List.of(
            Named.fast("json.JsonTest", json.JsonTest::run),
            Named.fast("jsonrpc2.Jsonrpc2Test", jsonrpc2.Jsonrpc2Test::run),
            Named.fast("jsonschema.JsonschemaTest", jsonschema.JsonschemaTest::run),
            Named.fast("settings.SettingsTest", settings.SettingsTest::run),
            Named.integration("actors.ActorsTest", actors.ActorsTest::run),
            Named.fast("markdown.MarkdownTest", markdown.MarkdownTest::run),
            Named.fast("application.ApplicationTest", application.ApplicationTest::run),
            Named.fast("peg.PegTest", peg.PegTest::run),
            Named.fast("argparse.ArgparseTest", argparse.ArgparseTest::run),
            Named.fast("symbols.SymbolsTest", symbols.SymbolsTest::run),
            Named.integration("symbols.SymbolIntegrationTest", symbols.SymbolsTest::integration),
            Named.fast("docs.DocsTest", docs.DocsTest::run),
            Named.integration("symbols.StoreTest", symbols.StoreTest::run),
            Named.fast("ffi.PlatformTest", ffi.PlatformTest::run),
            Named.fast("uritemplates.TemplatesTest", uritemplates.TemplatesTest::run),
            Named.fast("eventstream.EventStreamTest", eventstream.EventStreamTest::run),
            Named.integration("eventstream.EventStreamHttpTest", eventstream.EventStreamTest::integration),
            Named.fast("fetch.FetchTest", fetch.FetchTest::run),
            Named.integration("fetch.FetchHttpTest", fetch.FetchTest::integration),
            Named.fast("tcl.ReplTest", tcl.ReplTest::run),
            Named.fast("tcl.TclTest", tcl.TclTest::run),
            Named.fast("web.WebTest", web.WebTest::run),
            Named.integration("web.HttpIntegrationTest", web.WebTest::integration),
            Named.fast("web.FeaturesTest", web.FeaturesTest::run),
            Named.fast("web.ui.UiTest", web.ui.UiTest::run),
            Named.fast("web.assets.AssetsTest", web.assets.AssetsTest::run),
            Named.integration("web.hyperspec.HyperspecTest", web.hyperspec.HyperspecTest::run),
            Named.fast("web.sessions.SessionsTest", web.sessions.SessionsTest::run),
            Named.fast("web.uploads.UploadsTest", web.uploads.UploadsTest::run),
            Named.fast("web.forms.FormsTest", web.forms.FormsTest::run),
            Named.integration("web.cable.CableTest", web.cable.CableTest::run),
            Named.fast("web.dispatch.DispatchTest", web.dispatch.DispatchTest::run),
            Named.integration("browser.BrowserTest", browser.BrowserTest::run),
            Named.fast("project.ProjectTest", project.ProjectTest::run),
            Named.fast("project.AddTest", project.AddTest::run),
            Named.fast("project.MavenTest", project.MavenTest::run),
            Named.integration("project.AddIntegrationTest", project.AddIntegrationTest::run),
            Named.integration("project.ProjectIntegrationTest", project.ProjectTest::integration),
            Named.integration("project.SpecsTest", project.SpecsTest::run),
            Named.integration("sqlite3.SqliteTest", sqlite3.SqliteTest::run),
            Named.integration("sqlite3.ApiTest", sqlite3.ApiTest::run));

    var options = options(arguments, suites);
    for (var suite : suites) {
        if (selected(options, suite)) Check.suite(suite.name(), suite.body(), options.timeout());
    }
    System.exit(Check.report());
}

private static boolean selected(Options options, Named suite) {
    if (!options.name().isEmpty()) return options.name().equals(suite.name());
    return options.mode().equals("all") || suite.tier() == Tier.FAST;
}

private static Options options(String[] arguments, List<Named> suites) {
    var name = "";
    var mode = "fast";
    var timeout = Duration.ofMinutes(1);
    for (var at = 0; at < arguments.length; at++) {
        switch (arguments[at]) {
            case "--suite" -> {
                if (++at == arguments.length) usage("--suite needs a suite name");
                name = arguments[at];
            }
            case "--timeout" -> {
                if (++at == arguments.length) usage("--timeout needs seconds");
                try {
                    timeout = Duration.ofSeconds(Long.parseLong(arguments[at]));
                } catch (NumberFormatException invalid) {
                    usage("--timeout needs whole seconds");
                }
            }
            case "--mode" -> {
                if (++at == arguments.length) usage("--mode needs fast or all");
                mode = arguments[at];
                if (!mode.equals("fast") && !mode.equals("all")) usage("--mode needs fast or all");
            }
            case "--help" -> usage(null);
            default -> usage("unknown option: " + arguments[at]);
        }
    }
    var selectedName = name;
    if (!selectedName.isEmpty() && suites.stream().noneMatch(suite -> suite.name().equals(selectedName))) {
        usage("unknown suite: " + name);
    }
    if (timeout.isZero() || timeout.isNegative()) usage("--timeout must be positive");
    return new Options(name, mode, timeout);
}

private static void usage(String problem) {
    if (problem != null) System.err.println(problem);
    System.err.println("usage: run [--suite NAME] [--timeout SECONDS]");
    System.exit(problem == null ? 0 : 2);
}

private record Named(String name, Tier tier, Check.Suite body) {

    private static Named fast(String name, Check.Suite body) {
        return new Named(name, Tier.FAST, body);
    }

    private static Named integration(String name, Check.Suite body) {
        return new Named(name, Tier.INTEGRATION, body);
    }
}

private enum Tier {
    FAST,
    INTEGRATION
}

private record Options(String name, String mode, Duration timeout) {}
