package web;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import json.Json;
import web.assets.Served;
import web.ui.Html;

/// Putting a thing on the wire.
///
/// Every one of these is three lines a handler would otherwise write, and each
/// of the three is one somebody eventually forgets: the content type, the
/// status, and closing what was opened.
public final class Responses {

    /// The content type Turbo looks for when it decides whether a response is a
    /// stream of updates or a page.
    public static final String TURBO_STREAM = "text/vnd.turbo-stream.html";

    private Responses() {}

    public static void html(Html html, Response response) throws IOException {
        html(html, Status.OK, response);
    }

    public static void html(Html html, int status, Response response) throws IOException {
        response.status(status).header("Content-Type", "text/html; charset=utf-8");
        try (var out = response.writer()) {
            html.write(out);
        }
    }

    /// A Turbo Stream response — the same markup as a page, and a different
    /// content type, which is the whole of how Turbo tells them apart.
    public static void turbo(Html html, Response response) throws IOException {
        response.status(Status.OK).header("Content-Type", TURBO_STREAM + "; charset=utf-8");
        try (var out = response.writer()) {
            html.write(out);
        }
    }

    public static void text(String text, Response response) throws IOException {
        text(text, Status.OK, response);
    }

    public static void text(String text, int status, Response response) throws IOException {
        response.status(status).header("Content-Type", "text/plain; charset=utf-8");
        write(text, response);
    }

    public static void json(Json value, Response response) throws IOException {
        response.status(Status.OK).header("Content-Type", "application/json");
        try (var out = response.writer()) {
            value.write(out);
        }
    }

    /// A redirect, and a 303 by default, because a form submission that answers
    /// 302 is repeated as a POST by anything following the specification
    /// closely — Turbo among them.
    public static void redirect(String location, Response response) throws IOException {
        redirect(location, Status.SEE_OTHER, response);
    }

    public static void redirect(String location, int status, Response response) throws IOException {
        response.status(status).header("Location", location);
        response.close();
    }

    /// A status and nothing else: a 204, a 304, a 404 that has nothing to add.
    public static void empty(int status, Response response) throws IOException {
        response.status(status);
        response.close();
    }

    /// What `web.assets` decided, put on the wire. It answers with a status,
    /// headers and a body it has not read yet; this is the only place that
    /// knows both halves.
    public static void send(Served served, Response response) throws IOException {
        response.status(served.status());
        served.headers().forEach(response::header);
        if (served.body().isEmpty() || Status.bodiless(served.status())) {
            response.close();
            return;
        }
        try (var out = response.body()) {
            served.writeTo(out);
        }
    }

    /// Opens an event stream and answers with what to write signals to.
    ///
    /// The headers are the ones that keep an event stream alive on the way to a
    /// browser: no caching, no content encoding a proxy might buffer, and the
    /// nginx-specific instruction not to buffer at all — without which the page
    /// receives nothing until the response ends, which for an event stream is
    /// never.
    ///
    /// The writer is not closed here. That is the point of it: a caller writes
    /// to it and flushes for as long as the client is listening.
    public static Writer events(Response response) throws IOException {
        response.status(Status.OK)
                .header("Content-Type", "text/event-stream; charset=utf-8")
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no");
        var writer = response.writer();
        response.flush();
        return writer;
    }

    private static void write(String text, Response response) throws IOException {
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        response.header("Content-Length", String.valueOf(bytes.length));
        try (var out = response.body()) {
            out.write(bytes);
        }
    }
}
