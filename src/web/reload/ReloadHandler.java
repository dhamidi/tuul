package web.reload;

import java.util.Objects;
import reload.Capability;
import reload.Generation;
import reload.Lease;
import reload.Reload;
import web.Handler;
import web.Request;
import web.Response;
import web.Responses;
import web.Status;

/// Serves the handler attached to the generation admitted by a [Reload].
public final class ReloadHandler implements Handler {

    private final Reload reload;
    /// The capability that carries the HTTP handler in a generation.
    public static final Capability<Handler> HANDLER = Capability.create();

    /// Creates an adapter that leases `reload` around each HTTP request.
    /// A null coordinator throws `NullPointerException`.
    public ReloadHandler(Reload reload) {
        this.reload = Objects.requireNonNull(reload, "reload");
    }

    /// Attaches `handler` to `generation` under [#HANDLER].
    /// A null generation or handler throws `NullPointerException`.
    public static Generation attach(Generation generation, Handler handler) {
        return Objects.requireNonNull(generation, "generation")
                .with(HANDLER, Objects.requireNonNull(handler, "handler"));
    }

    /// Leases one generation and sends its attached handler the request.
    ///
    /// The adapter sends `503` when no generation is active or when the active
    /// generation has no handler for this adapter.
    /// A handler failure propagates after the adapter releases its lease.
    @Override
    public void handle(Request request, Response response) throws Exception {
        var acquired = reload.lease();
        if (acquired.isEmpty()) {
            Responses.empty(Status.SERVICE_UNAVAILABLE, response);
            return;
        }
        try (Lease lease = acquired.get()) {
            var handler = lease.generation().capability(HANDLER).orElse(null);
            if (handler == null) {
                Responses.empty(Status.SERVICE_UNAVAILABLE, response);
                return;
            }
            handler.handle(request, response);
        }
    }
}
