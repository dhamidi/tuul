package fetch;

import com.sun.net.httpserver.HttpServer;
import harness.Check;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.zip.GZIPOutputStream;

public final class FetchTest {
    private FetchTest() {}

    public static void run() throws Exception {
        modelsHeadersAndForms();
        sendsStreamsCookiesAndRedirects();
    }

    private static void modelsHeadersAndForms() throws Exception {
        var headers = Headers.of("Content-Type", "text/plain").add("X-Value", "one").add("x-value", "two");
        Check.equal("header names ignore case", java.util.List.of("one", "two"), headers.all("X-VALUE"));
        var fields = new LinkedHashMap<String, Object>();
        fields.put("q", "a b");
        fields.put("tag", new String[] {"x", "y"});
        Check.equal("form keeps repeated fields", "q=a+b&tag=x&tag=y", Body.form(Form.of(fields)).text(StandardCharsets.UTF_8));
    }

    private static void sendsStreamsCookiesAndRedirects() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "token=yes; Path=/");
            exchange.getResponseHeaders().add("Location", "/result");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/result", exchange -> {
            var value = exchange.getRequestHeaders().getFirst("Cookie") + ":hello";
            var encoded = new java.io.ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(encoded)) { gzip.write(value.getBytes(StandardCharsets.UTF_8)); }
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(200, encoded.size());
            exchange.getResponseBody().write(encoded.toByteArray());
            exchange.close();
        });
        server.start();
        try (var fetch = Fetch.virtualThreads(); var session = fetch.session().redirects(Redirects.BROWSER)) {
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/start");
            try (var response = session.get(uri).send()) {
                Check.equal("redirect returns the final status", 200, response.status());
                Check.equal("redirect records one hop", 1, response.history().size());
                Check.equal("cookie reaches redirected request and gzip is decoded", "token=yes:hello", response.text());
            }
        } finally { server.stop(0); }
    }
}
