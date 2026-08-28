package web;

import java.io.IOException;
import java.io.Writer;

/// Markup that writes itself into a response.
///
/// [Responses#html] needs one thing from a page: that it can put itself on a
/// writer. It does not need the shape of the markup, and asking for the shape
/// is what used to make `web` depend on `web.ui` — which is backwards, because
/// `web.ui` is built on the response this returns, not the other way round.
/// [web.ui.Html] is this, and is the only thing anybody should pass.
///
/// The method takes a [Writer] rather than answering with a String because a
/// page is written as it is built. A page that is a String was finished before
/// any of it was sent.
public interface Markup {

    void write(Writer out) throws IOException;
}
