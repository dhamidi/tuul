package reload;

import harness.Check;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;
import java.util.spi.ToolProvider;

/// Fast checks for generation-owned JDK tool metadata, invocation, and policy.
public final class JdkToolsTest {
    private JdkToolsTest() {}

    public static void run() {
        emptyBeforeActivation();
        listsAndRunsWithinTheGeneration();
        rejectsDuplicateNames();
        rejectsUnsupportedServices();
        exposesToolProviderPolicy();
    }

    private static void emptyBeforeActivation() {
        var reload = new Reload();
        var catalog = new JdkToolCatalog(reload);
        Check.equal("no tools are listed before activation", List.of(), catalog.list());
        Check.throwing("running without an active generation fails", () -> catalog.run(
                "echo", new PrintWriter(new StringWriter()), new PrintWriter(new StringWriter())));
        reload.close();
    }

    private static void listsAndRunsWithinTheGeneration() {
        var reload = new Reload();
        var tool = new FakeTool("echo", "writes its argument", 7);
        reload.submit("one", () -> Generation.empty().withServices(ToolProvider.class, List.of(tool)));
        var catalog = new JdkToolCatalog(reload);
        Check.equal("tool metadata does not expose the provider", List.of(
                new JdkToolCatalog.Tool("echo", "writes its argument")), catalog.list());
        var out = new StringWriter();
        var err = new StringWriter();
        var code = catalog.run("echo", new PrintWriter(out), new PrintWriter(err), "hello");
        Check.equal("tool exit code is returned", 7, code);
        Check.equal("tool runs with its arguments", "hello", out.toString());
        Check.equal("tool error output starts empty", "", err.toString());
        reload.close();
    }

    private static void rejectsDuplicateNames() {
        var reload = new Reload();
        var first = new FakeTool("same", "one", 0);
        var second = new FakeTool("same", "two", 0);
        reload.submit("duplicate", () -> Generation.empty().withServices(ToolProvider.class,
                List.of(second, first)));
        var catalog = new JdkToolCatalog(reload);
        try {
            catalog.list();
            Check.that("duplicate names reject deterministically", false);
        } catch (IllegalStateException failure) {
            Check.equal("duplicate name identifies the conflict", "duplicate JDK tool name: same",
                    failure.getMessage());
        }
        reload.close();
    }

    private static void rejectsUnsupportedServices() {
        Check.throwing("unsupported JDK services are rejected", () ->
                new JdkServiceFactory(Object.class));
    }

    private static void exposesToolProviderPolicy() {
        Check.that("ToolProvider is a supported generation service",
                JdkServiceFactory.supportedServices().contains(ToolProvider.class));
    }

    private static final class FakeTool implements ToolProvider {
        private final String name;
        private final String description;
        private final int code;

        private FakeTool(String name, String description, int code) {
            this.name = name;
            this.description = description;
            this.code = code;
        }

        @Override public String name() { return name; }
        @Override public Optional<String> description() { return Optional.of(description); }
        @Override public int run(PrintWriter out, PrintWriter err, String... arguments) {
            if (arguments.length > 0) out.print(arguments[0]);
            return code;
        }
    }
}
