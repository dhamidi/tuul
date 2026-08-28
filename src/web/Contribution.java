package web;

import java.io.IOException;
import java.io.Writer;
import web.assets.Assets;
import web.dispatch.Router;

/// Something a feature puts in the page.
///
/// A package that ships a controller usually needs one element in the document
/// as well — the cable needs a `div` that says which URL to listen to — and an
/// application that has to write that element itself can forget it. Forgetting
/// it breaks nothing that is visible: the page renders, and the thing the
/// package exists to do never happens.
///
/// It is written when the page is written, not when the feature is built,
/// because both of the things it needs are known only after every feature is
/// composed. A file's URL carries the digest of its content, so [Assets] has to
/// have read it; and a feature's own route may have been mounted under a
/// prefix, so [Router] has to hold the composed table. A feature that resolved
/// either at construction time would be right until an application mounted it
/// somewhere.
///
/// This writes to a [Writer] and knows nothing about markup, which is what
/// keeps `web` from depending on `web.ui`. A package that has markup writes
/// through it — `link(...).write(out)` — and a package that has none writes the
/// bytes.
@FunctionalInterface
public interface Contribution {

    void write(Assets assets, Router routes, Writer out) throws IOException;
}
