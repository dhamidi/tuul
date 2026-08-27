import harness.Check;

/// Every test, in one process. `mise run test`.

void main() throws Exception {
    json.JsonTest.run();
    application.ApplicationTest.run();
    peg.PegTest.run();
    argparse.ArgparseTest.run();
    symbols.SymbolsTest.run();
    symbols.StoreTest.run();
    ffi.PlatformTest.run();
    uritemplates.TemplatesTest.run();
    eventstream.EventStreamTest.run();
    web.WebTest.run();
    web.ui.UiTest.run();
    web.assets.AssetsTest.run();
    web.dispatch.DispatchTest.run();
    project.ProjectTest.run();
    sqlite3.SqliteTest.run();
    sqlite3.ApiTest.run();
    System.exit(Check.report());
}
