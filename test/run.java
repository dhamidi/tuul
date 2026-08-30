import harness.Check;
import java.time.Duration;
import java.util.List;

/// Runs all test suites, or one suite selected by `--suite`.
///
/// The runner prints a progress line before each suite and a duration-sorted
/// report after all selected suites finish. Each suite has a bounded timeout.

void main(String[] arguments) {
    var suites = List.of(
            new Named("json.JsonTest", json.JsonTest::run),
            new Named("jsonrpc2.Jsonrpc2Test", jsonrpc2.Jsonrpc2Test::run),
            new Named("jsonschema.JsonschemaTest", jsonschema.JsonschemaTest::run),
            new Named("actors.ActorsTest", actors.ActorsTest::run),
            new Named("markdown.MarkdownTest", markdown.MarkdownTest::run),
            new Named("application.ApplicationTest", application.ApplicationTest::run),
            new Named("peg.PegTest", peg.PegTest::run),
            new Named("argparse.ArgparseTest", argparse.ArgparseTest::run),
            new Named("symbols.SymbolsTest", symbols.SymbolsTest::run),
            new Named("docs.DocsTest", docs.DocsTest::run),
            new Named("symbols.StoreTest", symbols.StoreTest::run),
            new Named("ffi.PlatformTest", ffi.PlatformTest::run),
            new Named("uritemplates.TemplatesTest", uritemplates.TemplatesTest::run),
            new Named("eventstream.EventStreamTest", eventstream.EventStreamTest::run),
            new Named("fetch.FetchTest", fetch.FetchTest::run),
            new Named("tcl.ReplTest", tcl.ReplTest::run),
            new Named("tcl.TclTest", tcl.TclTest::run),
            new Named("web.WebTest", web.WebTest::run),
            new Named("web.FeaturesTest", web.FeaturesTest::run),
            new Named("web.ui.UiTest", web.ui.UiTest::run),
            new Named("web.assets.AssetsTest", web.assets.AssetsTest::run),
            new Named("web.hyperspec.HyperspecTest", web.hyperspec.HyperspecTest::run),
            new Named("web.controllers.ControllersTest", web.controllers.ControllersTest::run),
            new Named("web.forms.FormsTest", web.forms.FormsTest::run),
            new Named("web.cable.CableTest", web.cable.CableTest::run),
            new Named("web.dispatch.DispatchTest", web.dispatch.DispatchTest::run),
            new Named("browser.BrowserTest", browser.BrowserTest::run),
            new Named("project.ProjectTest", project.ProjectTest::run),
            new Named("project.SpecsTest", project.SpecsTest::run),
            new Named("sqlite3.SqliteTest", sqlite3.SqliteTest::run),
            new Named("sqlite3.ApiTest", sqlite3.ApiTest::run));

    var options = options(arguments, suites);
    for (var suite : suites) {
        if (options.name().isEmpty() || options.name().equals(suite.name())) {
            Check.suite(suite.name(), suite.body(), options.timeout());
        }
    }
    System.exit(Check.report());
}

private static Options options(String[] arguments, List<Named> suites) {
    var name = "";
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
            case "--help" -> usage(null);
            default -> usage("unknown option: " + arguments[at]);
        }
    }
    var selectedName = name;
    if (!selectedName.isEmpty() && suites.stream().noneMatch(suite -> suite.name().equals(selectedName))) {
        usage("unknown suite: " + name);
    }
    if (timeout.isZero() || timeout.isNegative()) usage("--timeout must be positive");
    return new Options(name, timeout);
}

private static void usage(String problem) {
    if (problem != null) System.err.println(problem);
    System.err.println("usage: run [--suite NAME] [--timeout SECONDS]");
    System.exit(problem == null ? 0 : 2);
}

private record Named(String name, Check.Suite body) {}

private record Options(String name, Duration timeout) {}
