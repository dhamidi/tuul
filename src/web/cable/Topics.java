package web.cable;

import java.util.List;
import web.Request;

/// Which topics a request is asking to hear about.
///
/// This is a function rather than a field because deciding what a client may
/// listen to is an application's business and nobody else's. A cable that read
/// the topics out of the query string on its own would be a cable that lets
/// anyone subscribe to anything, and it would be too late to fix once
/// applications depended on it.
@FunctionalInterface
public interface Topics {

    List<String> of(Request request) throws Exception;

    /// The same topics for everyone — a public feed, a status page.
    static Topics fixed(String... topics) {
        var always = List.of(topics);
        return request -> always;
    }

    /// Whatever the client asked for in the query string.
    ///
    /// **This trusts the client.** It is right for a topic that is already
    /// public and wrong for everything else: a client that can name a topic can
    /// hear it, so anything private has to be decided from the session instead.
    /// Signed topic names, the way ActionCable does it, would make this safe;
    /// until then this is for development and for feeds that have nothing to
    /// protect.
    static Topics query(String name) {
        return request -> request.query().all(name);
    }
}
