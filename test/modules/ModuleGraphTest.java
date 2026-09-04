package modules;

import harness.Check;
import java.lang.module.ModuleDescriptor;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// Fast, in-memory checks for the JPMS graph data model.
public final class ModuleGraphTest {

    private ModuleGraphTest() {}

    public static void run() {
        requiresKeepModifiers();
        descriptorFactsStayTogether();
        provenanceHasOptionalParts();
    }

    private static void requiresKeepModifiers() {
        var edge = new ModuleGraph.Requires("app", "library",
                Set.of(ModuleDescriptor.Requires.Modifier.TRANSITIVE,
                        ModuleDescriptor.Requires.Modifier.STATIC));
        Check.that("requires transitive is retained", edge.transitive());
        Check.that("requires static is retained", edge.staticPhase());
    }

    private static void descriptorFactsStayTogether() {
        var descriptor = ModuleDescriptor.newModule("app")
                .packages(Set.of("app", "app.spi"))
                .exports("app")
                .opens("app.spi", Set.of("framework"))
                .uses("app.spi.Service")
                .provides("app.spi.Service", List.of("app.Service"))
                .build();
        var node = new ModuleGraph.Node(new ModuleGraph.Id("app", descriptor.rawVersion()),
                descriptor,
                ModuleGraph.Origin.of(Path.of("app.jar"), "g:app:1", URI.create("file:app.jar")),
                descriptor.packages(), List.of(), List.of(new ModuleGraph.Export("app", "app", Set.of())),
                List.of(new ModuleGraph.Open("app", "app.spi", Set.of("framework"))),
                descriptor.uses(), List.of(new ModuleGraph.Provides("app", "app.spi.Service", List.of("app.Service"))));
        Check.equal("the descriptor package set is retained", Set.of("app", "app.spi"), node.packages());
        Check.that("qualified opens are retained", node.opens().getFirst().qualified());
        Check.equal("service providers are retained", List.of("app.Service"), node.provides().getFirst().providers());
    }

    private static void provenanceHasOptionalParts() {
        var system = ModuleGraph.Origin.system(URI.create("jrt:/java.base"));
        Check.that("system provenance has no artifact path", system.path().isEmpty());
        Check.equal("system provenance has its location", Optional.of(URI.create("jrt:/java.base")),
                system.location());
    }

}
