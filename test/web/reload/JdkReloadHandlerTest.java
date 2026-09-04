package web.reload;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import harness.Check;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import modules.MemoryModule;
import modules.MemoryModuleFinder;
import java.util.Map;
import reload.Generation;
import reload.CandidateContext;
import reload.Reload;
import reload.Revision;
import web.Responses;

/// Fast checks for the JDK HTTP adapter's lease and drain boundary.
public final class JdkReloadHandlerTest {

    private JdkReloadHandlerTest() {}

    public static void run() throws Exception {
        unavailableWithoutGeneration();
        servesAndDrains();
        switchesContributionKinds();
        rejectsMissingProvider();
        rejectsAmbiguousContributions();
        rejectsMultipleProviders();
    }

    private static void rejectsMissingProvider() throws Exception {
        var descriptor = ModuleDescriptor.newModule("empty.external").build();
        var finder = MemoryModuleFinder.of(new MemoryModule(descriptor, Map.of()));
        var configuration = ModuleLayer.boot().configuration().resolve(ModuleFinder.of(), finder,
                List.of("empty.external"));
        var layer = ModuleLayer.boot().defineModulesWithOneLoader(configuration,
                JdkReloadHandlerTest.class.getClassLoader());
        try {
            new JdkGenerationFactory().define(new CandidateContext(layer,
                    layer.findModule("empty.external").orElseThrow()));
            throw new AssertionError("missing provider was accepted");
        } catch (IllegalStateException expected) {
            Check.that("missing provider reports a clear failure",
                    expected.getMessage().contains("root module empty.external must provide exactly one HTTP")
                            && expected.getMessage().contains("found none"));
        }
    }

    private static void rejectsAmbiguousContributions() throws Exception {
        var descriptor = ModuleDescriptor.newModule("ambiguous.external")
                .requires("tuul")
                .requires("jdk.httpserver")
                .provides("reload.Program", List.of("app.Program"))
                .provides("com.sun.net.httpserver.HttpHandler", List.of("app.Handler"))
                .build();
        var finder = MemoryModuleFinder.of(new MemoryModule(descriptor, Map.of()));
        var configuration = ModuleLayer.boot().configuration().resolve(ModuleFinder.of(), finder,
                List.of("ambiguous.external"));
        var layer = ModuleLayer.boot().defineModulesWithOneLoader(configuration,
                JdkReloadHandlerTest.class.getClassLoader());
        try {
            new JdkGenerationFactory().define(new CandidateContext(layer,
                    layer.findModule("ambiguous.external").orElseThrow()));
            throw new AssertionError("ambiguous contribution was accepted");
        } catch (IllegalStateException expected) {
            Check.that("both contribution kinds report deterministic providers",
                    expected.getMessage().contains("ambiguous.external/app.Handler")
                            && expected.getMessage().contains("ambiguous.external/app.Program"));
        }
    }

    private static void rejectsMultipleProviders() throws Exception {
        var descriptor = ModuleDescriptor.newModule("multiple.external")
                .requires("jdk.httpserver")
                .provides("com.sun.net.httpserver.HttpHandler", List.of("app.One", "app.Two"))
                .build();
        var finder = MemoryModuleFinder.of(new MemoryModule(descriptor, Map.of()));
        var configuration = ModuleLayer.boot().configuration().resolve(ModuleFinder.of(), finder,
                List.of("multiple.external"));
        var layer = ModuleLayer.boot().defineModulesWithOneLoader(configuration,
                JdkReloadHandlerTest.class.getClassLoader());
        try {
            new JdkGenerationFactory().define(new CandidateContext(layer,
                    layer.findModule("multiple.external").orElseThrow()));
            throw new AssertionError("multiple providers were accepted");
        } catch (IllegalStateException expected) {
            Check.that("multiple providers report deterministic names",
                    expected.getMessage().contains("multiple.external/app.One")
                            && expected.getMessage().contains("multiple.external/app.Two"));
        }
    }

    private static void unavailableWithoutGeneration() throws IOException {
        var reload = new Reload();
        var handler = new JdkReloadHandler(reload);
        var exchange = new Exchange();
        handler.handle(exchange);
        Check.equal("no active JDK generation answers unavailable", 503, exchange.status);
        reload.close();
    }

    private static void servesAndDrains() throws Exception {
        var reload = new Reload();
        var handler = new JdkReloadHandler(reload);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closed = new AtomicInteger();
        var first = (com.sun.net.httpserver.HttpHandler) exchange -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            var body = "one".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        };
        reload.submit(Revision.of("one", () -> JdkReloadHandler.attach(
                Generation.empty().closing(closed::incrementAndGet), first)));
        var request = new Exchange();
        var running = Thread.ofVirtual().start(() -> {
            try { handler.handle(request); }
            catch (IOException failure) { throw new RuntimeException(failure); }
        });
        Check.that("the request leases the first generation", entered.await(2, TimeUnit.SECONDS));
        reload.submit(Revision.of("two", () -> JdkReloadHandler.attach(Generation.empty(),
                exchange -> {
                    var body = "two".getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                })));
        Check.equal("the retired provider stays open while leased", 0, closed.get());
        release.countDown();
        running.join();
        Check.equal("the retired provider closes after its lease drains", 1, closed.get());
        Check.equal("the first handler answered", 200, request.status);
        reload.close();
    }

    private static void switchesContributionKinds() throws Exception {
        var reload = new Reload();
        var ingress = new JdkReloadHandler(reload);
        reload.submit(Revision.of("tuul", () -> ReloadHandler.attach(Generation.empty(),
                (request, response) -> Responses.text("tuul\n", response))));
        var tuul = new Exchange();
        ingress.handle(tuul);
        Check.equal("the stable ingress serves a Tuul handler", 200, tuul.status);
        Check.equal("the Tuul contribution answers", "tuul\n", tuul.body.toString());

        reload.submit(Revision.of("jdk", () -> JdkReloadHandler.attach(Generation.empty(),
                exchange -> {
                    var body = "jdk\n".getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                })));
        var jdk = new Exchange();
        ingress.handle(jdk);
        Check.equal("the same ingress switches to a raw JDK handler", 200, jdk.status);
        Check.equal("the raw contribution answers", "jdk\n", jdk.body.toString());
        reload.close();
    }

    private static final class Exchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private int status = -1;

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return URI.create("http://localhost/"); }
        @Override public String getRequestMethod() { return "GET"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public java.io.InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public java.io.OutputStream getResponseBody() { return body; }
        @Override public void sendResponseHeaders(int status, long length) { this.status = status; }
        @Override public int getResponseCode() { return status; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress(0); }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress(0); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(java.io.InputStream input, java.io.OutputStream output) {}
        @Override public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return null; }
    }
}
