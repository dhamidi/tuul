package web.reload;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Objects;
import reload.Capability;
import reload.Generation;
import reload.Lease;
import reload.Reload;

/// Serves a raw JDK [com.sun.net.httpserver.HttpHandler] from the active
/// generation. The application module only needs `jdk.httpserver`; it does
/// not need `tuul`, `reload`, or any `web` type.
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
            if (handler == null) {
                unavailable(exchange);
                return;
            }
            handler.handle(exchange);
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
