import harness.Check;

/// Every test, in one process. `mise run test`.

void main() throws Exception {
    json.JsonTest.run();
    application.ApplicationTest.run();
    symbols.SymbolsTest.run();
    project.ProjectTest.run();
    sqlite.SqliteTest.run();
    System.exit(Check.report());
}
