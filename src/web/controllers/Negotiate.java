package web.controllers;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import web.Request;
import web.Response;
import web.Responses;
import web.Status;
import web.ui.Html;

/// Answering the same question differently depending on who asked.
///
/// Content negotiation is usually three lines of string matching per handler,
/// which is three lines per handler to get wrong. [Accept] does the reading;
/// this does the deciding, and holds the one decision a hypermedia application
/// makes constantly.
public final class Negotiate {

    /// What Turbo puts in `Accept` when it is able to apply a stream of updates
    /// rather than replace a page.
    public static final String TURBO_STREAM = Responses.TURBO_STREAM;

    public static final String HTML = "text/html";

    public static final String JSON = "application/json";

    private Negotiate() {}

    public static Optional<String> best(Request request, String... offers) {
        return Accept.of(request).best(List.of(offers));
    }

    public static boolean accepts(Request request, String media) {
        return Accept.of(request).accepts(media);
    }

    /// Whether this request can take a Turbo Stream.
    ///
    /// Deliberately not "is this Turbo" — a request that merely came from a page
    /// running Turbo Drive still wants a page. The stream content type appearing
    /// in `Accept` is Turbo saying it has somewhere to put the updates.
    ///
    /// And deliberately not "does it accept the stream type", which every
    /// browser does: an ordinary page request ends in `*/*;q=0.8`, so it accepts
    /// everything including this. The question is whether it wants a stream
    /// *at least as much as* a page, which is true only when something named
    /// the type rather than falling into a wildcard.
    public static boolean wantsStream(Request request) {
        var accept = Accept.of(request);
        var stream = accept.quality(TURBO_STREAM);
        return stream > 0 && stream >= accept.quality(HTML);
    }

    /// The answer to a form submission, both ways.
    ///
    /// Turbo asked for a stream, so it gets the updates and stays where it is;
    /// anything else — a browser with the JavaScript disabled, a crawler, curl —
    /// gets a redirect to somewhere it can see the result. One call, because a
    /// handler that only writes the first half works right up until somebody
    /// turns Turbo off.
    ///
    /// The redirect is a 303: a 302 is repeated as a POST by anything that
    /// follows the specification, Turbo included.
    public static void stream(Request request, Response response, Html updates, String location) throws IOException {
        if (wantsStream(request)) Responses.turbo(updates, response);
        else Responses.redirect(location, Status.SEE_OTHER, response);
    }
}
