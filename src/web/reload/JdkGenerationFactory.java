package web.reload;

import com.sun.net.httpserver.HttpHandler;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import reload.CandidateContext;
import reload.Generation;
import reload.GenerationFactory;
import reload.JdkServiceFactory;
import reload.Program;
import reload.ServiceGenerationFactory;

/// Builds a generation from one HTTP contribution in the compiled root module.
///
/// The root descriptor may provide [Program] or the JDK [HttpHandler]. The
/// host owns the stable listener and supplies this factory to the compiler.
public final class JdkGenerationFactory implements GenerationFactory {

    /// Creates a factory which defines one generation per candidate root.
    public JdkGenerationFactory() {}

    /// Loads exactly one root HTTP contribution and attaches it to a generation.
    /// Root service providers and supported JDK service providers are owned by
    /// that generation and close after all leases admitted to it drain.
    @Override
    public Generation define(CandidateContext candidate) throws Exception {
        Objects.requireNonNull(candidate, "candidate");
        var contributions = contributions(candidate);
        if (contributions.isEmpty()) throw problem(candidate, "found none");
        if (contributions.size() != 1) {
            throw problem(candidate, "found " + contributions.size() + " "
                    + contributions.stream().map(Contribution::display).toList());
        }
        var primary = contributions.getFirst().program() ? program(candidate) : handler(candidate);
        try {
            var auxiliary = new JdkServiceFactory(JdkServiceFactory.supportedServices()).define(candidate);
            return Generation.merge(List.of(primary, auxiliary));
        } catch (Throwable failure) {
            try { primary.close(); } catch (Exception close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    private static Generation program(CandidateContext candidate) throws Exception {
        var services = new ServiceGenerationFactory(Program.class).define(candidate);
        var programs = services.services(Program.class);
        if (programs.size() != 1) {
            close(services);
            throw problem(candidate, "found " + programs.size() + " "
                    + candidate.declarations(Program.class).stream()
                            .map(name -> candidate.root().getName() + "/" + name).toList());
        }
        try {
            return Generation.merge(List.of(services, programs.getFirst().define()));
        } catch (Throwable failure) {
            try { services.close(); } catch (Exception close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    private static Generation handler(CandidateContext candidate) throws Exception {
        var services = new ServiceGenerationFactory(HttpHandler.class).define(candidate);
        var handlers = services.services(HttpHandler.class);
        if (handlers.size() != 1) {
            close(services);
            throw problem(candidate, "found " + handlers.size() + " "
                    + candidate.declarations(HttpHandler.class).stream()
                            .map(name -> candidate.root().getName() + "/" + name).toList());
        }
        try {
            return Generation.merge(List.of(services,
                    JdkReloadHandler.attach(Generation.empty(), handlers.getFirst())));
        } catch (Throwable failure) {
            try { services.close(); } catch (Exception close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    private static List<Contribution> contributions(CandidateContext candidate) {
        var answer = new ArrayList<Contribution>();
        candidate.declarations(Program.class).stream()
                .map(name -> new Contribution(true, candidate.root().getName(), name))
                .forEach(answer::add);
        candidate.declarations(HttpHandler.class).stream()
                .map(name -> new Contribution(false, candidate.root().getName(), name))
                .forEach(answer::add);
        return answer.stream().sorted(Comparator.comparing(Contribution::display)).toList();
    }

    private static IllegalStateException problem(CandidateContext candidate, String found) {
        return new IllegalStateException("root module " + candidate.root().getName()
                + " must provide exactly one HTTP contribution (reload.Program or "
                + HttpHandler.class.getName() + "); " + found);
    }

    private static void close(Generation generation) {
        try { generation.close(); } catch (Exception ignored) {}
    }

    private record Contribution(boolean program, String module, String provider) {
        private String display() { return module + "/" + provider; }
    }
}
