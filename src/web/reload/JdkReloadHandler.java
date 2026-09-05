package web.reload;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.io.IOException;
import java.util.Objects;
import reload.Capability;
import reload.CandidateContext;
import reload.Generation;
import reload.JdkServices;
import reload.Lease;
import reload.Program;
import reload.Reload;
import web.Handler;
import web.serve.Http;

/// Dispatches one stable JDK listener to the active HTTP contribution.
///
/// A generation may carry either a raw JDK handler or a Tuul [Handler]. The
/// ingress selects one while holding one lease, so a retired module layer
/// remains reachable only until its admitted exchanges return.
public final class JdkReloadHandler implements com.sun.net.httpserver.HttpHandler {

    /// The capability carrying the external module's current JDK handler.
    public static final Capability<com.sun.net.httpserver.HttpHandler> HANDLER = Capability.create();

    private final Reload reload;

    /// Creates an adapter that leases the active generation for each exchange.
    public JdkReloadHandler(Reload reload) {
        this.reload = Objects.requireNonNull(reload, "reload");
    }

    /// Attaches a raw JDK handler to a generation.
    public static Generation attach(Generation generation, com.sun.net.httpserver.HttpHandler handler) {
        return Objects.requireNonNull(generation, "generation")
                .with(HANDLER, Objects.requireNonNull(handler, "handler"));
    }

    /// Defines the generation for one root HTTP contribution.
    ///
    /// The root may provide exactly one [Program] or exactly one JDK
    /// [HttpHandler]. Supported JDK services are attached to the same
    /// generation and share its lease and close boundary.
    public static Generation generation(CandidateContext candidate) throws Exception {
        Objects.requireNonNull(candidate, "candidate");
        var contributions = contributions(candidate);
        if (contributions.isEmpty()) throw problem(candidate, "found none");
        if (contributions.size() != 1) {
            throw problem(candidate, "found " + contributions.size() + " "
                    + contributions.stream().map(Contribution::display).toList());
        }
        var primary = contributions.getFirst().program() ? program(candidate) : handler(candidate);
        try {
            var auxiliary = JdkServices.define(candidate, JdkServices.supported());
            return Generation.merge(List.of(primary, auxiliary));
        } catch (Throwable failure) {
            try { primary.close(); } catch (Exception close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    private static Generation program(CandidateContext candidate) throws Exception {
        var services = Generation.services(candidate, Program.class);
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
        var services = Generation.services(candidate, HttpHandler.class);
        var handlers = services.services(HttpHandler.class);
        if (handlers.size() != 1) {
            close(services);
            throw problem(candidate, "found " + handlers.size() + " "
                    + candidate.declarations(HttpHandler.class).stream()
                            .map(name -> candidate.root().getName() + "/" + name).toList());
        }
        try {
            return Generation.merge(List.of(services,
                    attach(Generation.empty(), handlers.getFirst())));
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

    /// Leases one generation and invokes its raw JDK handler.
    ///
    /// A request received before the first successful activation gets a 503.
    /// The lease remains held until the handler returns, so retiring a module
    /// layer waits for all exchanges admitted to that layer.
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Objects.requireNonNull(exchange, "exchange");
        var acquired = reload.lease();
        if (acquired.isEmpty()) {
            unavailable(exchange);
            return;
        }
        try (Lease lease = acquired.get()) {
            var handler = lease.generation().capability(HANDLER).orElse(null);
            if (handler != null) {
                handler.handle(exchange);
                return;
            }
            var tuul = lease.generation().capability(ReloadHandler.HANDLER).orElse(null);
            if (tuul != null) {
                try {
                    Http.handle(tuul, exchange);
                } catch (IOException failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new IOException(failure);
                }
                return;
            }
            unavailable(exchange);
        }
    }

    private static void unavailable(HttpExchange exchange) throws IOException {
        var body = "service unavailable\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(503, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
