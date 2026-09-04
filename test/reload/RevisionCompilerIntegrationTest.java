package reload;

/// Runs the real named-module reload compiler integration checks.
public final class RevisionCompilerIntegrationTest {

    private RevisionCompilerIntegrationTest() {}

    /// Compiles and loads a two-module revision closure in a fresh layer.
    public static void run() throws Exception {
        RevisionCompilerModuleLayerTest.run();
    }
}
