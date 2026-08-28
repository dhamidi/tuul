import harness.Check;

/// Every test, in one process. `mise run test`.

void main() throws Exception {
    json.JsonTest.run();
    jsonrpc2.Jsonrpc2Test.run();
    jsonschema.JsonschemaTest.run();
    actors.ActorsTest.run();
    markdown.MarkdownTest.run();
    application.ApplicationTest.run();
    peg.PegTest.run();
    argparse.ArgparseTest.run();
    symbols.SymbolsTest.run();
    docs.DocsTest.run();
    symbols.StoreTest.run();
    ffi.PlatformTest.run();
    uritemplates.TemplatesTest.run();
    eventstream.EventStreamTest.run();
    web.WebTest.run();
    web.ui.UiTest.run();
    web.assets.AssetsTest.run();
    web.hyperspec.HyperspecTest.run();
    web.controllers.ControllersTest.run();
    web.forms.FormsTest.run();
    web.cable.CableTest.run();
    web.dispatch.DispatchTest.run();
    browser.BrowserTest.run();
    project.ProjectTest.run();
    project.SpecsTest.run();
    sqlite3.SqliteTest.run();
    sqlite3.ApiTest.run();
    System.exit(Check.report());
}
