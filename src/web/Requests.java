package web;

import java.io.IOException;
import application.Message;
import json.Json;

/// Turning a request into a message.
///
/// This is the join between `web` and `application`, and it is deliberately a
/// convention rather than a mechanism: the type of the message is the name of
/// the route that recognised it, and the payload is everything the client said,
/// in one place. An update function then reads `params` and never learns what
/// HTTP is.
///
/// Path variables, query parameters and a submitted form are merged, in that
/// order of increasing specificity — a variable in the path beats a query
/// parameter of the same name, because the path is the resource and the query
/// is a remark about it.
public final class Requests {

    /// The type a message gets when nothing routed the request. A handler
    /// mounted directly still works; it just has a duller name.
    public static final String REQUEST = "request";

    private Requests() {}

    /// Everything the client said, merged. Reads the body when it is a form,
    /// which consumes it — a handler that wants the raw bytes should take them
    /// before asking for this.
    public static Parameters params(Request request) throws IOException {
        return Routing.variables(request).and(request.query()).and(request.form());
    }

    /// The message this request is, for an application to update on.
    public static Message message(Request request) throws IOException {
        return message(request, params(request));
    }

    /// The same, when the parameters have already been read — which is what a
    /// handler that has parsed a multipart body itself will have.
    public static Message message(Request request, Parameters params) {
        var body = Json.Object.of()
                .with("method", request.method())
                .with("path", request.path())
                .with("params", params.json());
        return Message.of(Routing.route(request).orElse(REQUEST), body);
    }
}
