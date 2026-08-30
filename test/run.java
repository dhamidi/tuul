import harness.Check;

/// Every test, in one process. `mise run test`.

void main() throws Exception {
    Check.suite("json.JsonTest", json.JsonTest::run);
    Check.suite("jsonrpc2.Jsonrpc2Test", jsonrpc2.Jsonrpc2Test::run);
    Check.suite("jsonschema.JsonschemaTest", jsonschema.JsonschemaTest::run);
    Check.suite("actors.ActorsTest", actors.ActorsTest::run);
    Check.suite("markdown.MarkdownTest", markdown.MarkdownTest::run);
    Check.suite("application.ApplicationTest", application.ApplicationTest::run);
    Check.suite("peg.PegTest", peg.PegTest::run);
    Check.suite("argparse.ArgparseTest", argparse.ArgparseTest::run);
    Check.suite("symbols.SymbolsTest", symbols.SymbolsTest::run);
    Check.suite("docs.DocsTest", docs.DocsTest::run);
    Check.suite("symbols.StoreTest", symbols.StoreTest::run);
    Check.suite("ffi.PlatformTest", ffi.PlatformTest::run);
    Check.suite("uritemplates.TemplatesTest", uritemplates.TemplatesTest::run);
    Check.suite("eventstream.EventStreamTest", eventstream.EventStreamTest::run);
    Check.suite("fetch.FetchTest", fetch.FetchTest::run);
    Check.suite("tcl.ReplTest", tcl.ReplTest::run);
    Check.suite("tcl.TclTest", tcl.TclTest::run);
    Check.suite("web.WebTest", web.WebTest::run);
    Check.suite("web.FeaturesTest", web.FeaturesTest::run);
    Check.suite("web.ui.UiTest", web.ui.UiTest::run);
    Check.suite("web.assets.AssetsTest", web.assets.AssetsTest::run);
    Check.suite("web.hyperspec.HyperspecTest", web.hyperspec.HyperspecTest::run);
    Check.suite("web.controllers.ControllersTest", web.controllers.ControllersTest::run);
    Check.suite("web.forms.FormsTest", web.forms.FormsTest::run);
    Check.suite("web.cable.CableTest", web.cable.CableTest::run);
    Check.suite("web.dispatch.DispatchTest", web.dispatch.DispatchTest::run);
    Check.suite("browser.BrowserTest", browser.BrowserTest::run);
    Check.suite("project.ProjectTest", project.ProjectTest::run);
    Check.suite("project.SpecsTest", project.SpecsTest::run);
    Check.suite("sqlite3.SqliteTest", sqlite3.SqliteTest::run);
    Check.suite("sqlite3.ApiTest", sqlite3.ApiTest::run);
    System.exit(Check.report());
}
