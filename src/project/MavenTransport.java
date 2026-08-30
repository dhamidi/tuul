package project;

import fetch.HttpException;
import fetch.Response;
import fetch.Session;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

/// Runs retryable Maven GET requests within global and per-origin limits.
///
/// The transport retries connection and response-body failures. It also retries
/// HTTP 408, 425, 429, 500, 502, 503, and 504. It honors `Retry-After`. Four
/// attempts bound each request. HTTP 404 and other permanent client errors do
/// not retry.
final class MavenTransport {
    static final int MAX_ATTEMPTS = 4;
    static final int GLOBAL_LIMIT = 8;
    static final int ORIGIN_LIMIT = 4;
    private static final Set<Integer> RETRYABLE = Set.of(408, 425, 429, 500, 502, 503, 504);

    private final Session session;
    private final Semaphore global = new Semaphore(GLOBAL_LIMIT);
    private final Map<String, Semaphore> origins = new ConcurrentHashMap<>();

    MavenTransport(Session session) {
        this.session = session;
    }

    <T> T get(URI uri, String coordinate, String kind, Handler<T> handler) throws IOException {
        IOException last = null;
        for (var attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            var origin = origins.computeIfAbsent(origin(uri), ignored -> new Semaphore(ORIGIN_LIMIT));
            acquire(global, origin, coordinate, kind, uri, attempt);
            try (var response = session.get(uri).timeout(Duration.ofMinutes(2)).send()) {
                if (response.status() == 404) throw new Missing(detail(coordinate, kind, uri,
                        "headers", attempt, "HTTP 404"));
                if (!response.successful()) {
                    if (!RETRYABLE.contains(response.status()) || attempt == MAX_ATTEMPTS) {
                        throw new IOException(detail(coordinate, kind, uri, "headers", attempt,
                                "HTTP " + response.status()));
                    }
                    waitBeforeRetry(response, attempt);
                    continue;
                }
                try {
                    return handler.read(response);
                } catch (Permanent failure) {
                    throw failure;
                } catch (IOException failure) {
                    last = new IOException(detail(coordinate, kind, uri, "body", attempt,
                            message(failure)), failure);
                    if (attempt == MAX_ATTEMPTS) throw last;
                    waitBeforeRetry(null, attempt);
                }
            } catch (Permanent failure) {
                throw failure;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(detail(coordinate, kind, uri, "interrupted", attempt,
                        "request cancelled"), interrupted);
            } catch (HttpException failure) {
                last = new IOException(detail(coordinate, kind, uri, "headers", attempt,
                        "HTTP " + failure.status()), failure);
                if (!RETRYABLE.contains(failure.status()) || attempt == MAX_ATTEMPTS) throw last;
                waitBeforeRetry(null, attempt);
            } catch (IOException failure) {
                last = new IOException(detail(coordinate, kind, uri, "transport", attempt,
                        message(failure)), failure);
                if (attempt == MAX_ATTEMPTS) throw last;
                waitBeforeRetry(null, attempt);
            } finally {
                origin.release();
                global.release();
            }
        }
        throw last == null ? new IOException(detail(coordinate, kind, uri, "transport", 0,
                "request did not run")) : last;
    }

    private static void acquire(Semaphore global, Semaphore origin, String coordinate,
            String kind, URI uri, int attempt) throws IOException {
        var acquiredGlobal = false;
        try {
            global.acquire();
            acquiredGlobal = true;
            origin.acquire();
        } catch (InterruptedException interrupted) {
            if (acquiredGlobal) global.release();
            Thread.currentThread().interrupt();
            throw new IOException(detail(coordinate, kind, uri, "limiter", attempt,
                    "request cancelled"), interrupted);
        }
    }

    private static void waitBeforeRetry(Response response, int attempt) throws IOException {
        var delay = retryAfter(response);
        if (delay < 0) {
            var base = 25L << (attempt - 1);
            delay = base + ThreadLocalRandom.current().nextLong(base / 2 + 1);
        }
        try {
            Thread.sleep(Math.min(delay, 5_000));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Maven retry interrupted after " + attempt + " attempt(s)", interrupted);
        }
    }

    private static long retryAfter(Response response) {
        if (response == null) return -1;
        var value = response.headers().first("Retry-After", "").trim();
        if (value.isEmpty()) return -1;
        try {
            return Math.max(0, Long.parseLong(value) * 1_000);
        } catch (NumberFormatException notSeconds) {
            try {
                return Math.max(0, Duration.between(ZonedDateTime.now(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)).toMillis());
            } catch (RuntimeException invalidDate) {
                return -1;
            }
        }
    }

    private static String origin(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort();
    }

    private static String detail(String coordinate, String kind, URI uri, String phase,
            int attempts, String reason) {
        return coordinate + " " + kind + " from " + uri + " failed during " + phase
                + " after " + attempts + " attempt(s): " + reason;
    }

    private static String message(Exception failure) {
        return failure.getMessage() == null ? failure.toString() : failure.getMessage();
    }

    interface Handler<T> {
        T read(Response response) throws IOException;
    }

    static class Permanent extends IOException {
        Permanent(String message) { super(message); }
        Permanent(String message, Throwable cause) { super(message, cause); }
    }

    static final class Missing extends Permanent {
        Missing(String message) { super(message); }
    }
}
