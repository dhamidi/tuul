package web.hyperspec;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import web.Headers;

/// One client of the application: a cookie jar, the page it is looking at, and
/// the requests it has made.
///
/// Two agents are two people. Each has its own cookie jar, which is the whole
/// of what a session is over HTTP, so a spec can have Alice sign in without
/// Bob's client knowing anything about it.
///
/// Redirects are **not** followed. A hypermedia client following a redirect is
/// a step in the conversation — it is how a form submission becomes a page —
/// and a spec that hides it cannot tell a 303 apart from a 200 that happened to
/// render the same thing. `follow redirect` is a command for that reason.
public final class Agent {

    /// A spec that hangs is worse than one that fails, and a service that has
    /// stopped answering is a result.
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    /// What the client did, for the assertions that are about the conversation
    /// rather than about a page.
    public record Exchange(String method, String path, int status) {}

    private final String name;
    private final URI service;
    private final HttpClient http;
    private final List<Exchange> history = new ArrayList<>();
    private Page page;

    Agent(String name, URI service) {
        this.name = name;
        this.service = service;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(PATIENCE)
                .build();
    }

    public String name() {
        return name;
    }

    public Page page() {
        return page;
    }

    public List<Exchange> history() {
        return List.copyOf(history);
    }

    /// Where this client is: the path it last asked for, which is what a spec
    /// means by navigation state.
    public String at() {
        if (page == null) return "";
        var query = page.uri().getRawQuery();
        return page.uri().getRawPath() + (query == null ? "" : "?" + query);
    }

    public Page visit(String path) {
        return request("GET", resolve(path), null);
    }

    public Page follow(Page.Link link) {
        return request("GET", resolve(link.href()), null);
    }

    /// Follows the redirect the last response asked for. A GET, because every
    /// redirect a hypermedia application sends is one — 303 says so, and 302
    /// means it in practice.
    public Page followRedirect() {
        if (page == null || !page.redirect()) {
            throw new SpecException(0, "there is no redirect to follow"
                    + (page == null ? "" : " — the last response was " + page.status()));
        }
        return request("GET", resolve(page.location().orElseThrow()), null);
    }

    public Page submit(Page.Form form, Map<String, String> filled) {
        var values = new LinkedHashMap<>(form.values());
        values.putAll(filled);
        var body = encode(values);
        var action = form.action().isEmpty() ? at() : form.action();
        if (form.method().equals("GET")) {
            return request("GET", resolve(action + (body.isEmpty() ? "" : "?" + body)), null);
        }
        return request(form.method(), resolve(action), body);
    }

    private URI resolve(String reference) {
        var base = page == null ? service : page.uri();
        return base.resolve(reference);
    }

    private Page request(String method, URI uri, String body) {
        var builder = HttpRequest.newBuilder(uri).timeout(PATIENCE);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/x-www-form-urlencoded")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        try {
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            page = read(method, uri, response);
            history.add(new Exchange(method, uri.getRawPath(), response.statusCode()));
            return page;
        } catch (IOException e) {
            throw new SpecException(0, method + " " + uri + " failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpecException(0, "interrupted while asking for " + uri);
        }
    }

    /// A response that is not HTML still has a status, a location and a place
    /// in the history — a redirect has no body at all — so it becomes a page
    /// with nothing in it rather than an error.
    private static Page read(String method, URI uri, HttpResponse<String> response) {
        var headers = headers(response);
        var type = headers.first("Content-Type", "").toLowerCase(Locale.ROOT);
        var html = type.startsWith("text/html") || type.startsWith("text/vnd.turbo-stream.html");
        var root = html && !response.body().isBlank()
                ? Document.read(response.body())
                : new Document.Element("#document", Map.of(), List.of());
        return new Page(uri, method, response.statusCode(), headers, response.body(), root);
    }

    private static Headers headers(HttpResponse<String> response) {
        var headers = Headers.of();
        for (var entry : response.headers().map().entrySet()) {
            for (var value : entry.getValue()) headers = headers.add(entry.getKey(), value);
        }
        return headers;
    }

    private static String encode(Map<String, String> values) {
        var encoded = new StringBuilder();
        values.forEach((name, value) -> {
            if (!encoded.isEmpty()) encoded.append('&');
            encoded.append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return encoded.toString();
    }
}
