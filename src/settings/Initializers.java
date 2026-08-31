package settings;

import application.Effect;
import application.Message;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import json.Json;

/// Builds effect handlers for settings initializer sources.
///
/// A source handles one scheme from a `settings.resolve` effect. A successful
/// read emits one `initialize` message. A missing source emits nothing. An
/// invalid source calls the configured failure consumer and emits nothing.
///
/// Register the returned handler with [actors.ActorSystem#effect] under
/// `Settings.RESOLVE`. Combine standard and custom sources with [#sources].
///
/// ```
/// var failures = new java.util.concurrent.CopyOnWriteArrayList<Initializers.Failure>();
/// var handler = Initializers.environment(System::getenv, failures::add);
/// var settings = Settings.of(images);
/// try (var system = actors.ActorSystem.named("shop")
///         .define(settings).effect(Settings.RESOLVE, handler)) {
///     system.summon(Settings.address());
/// }
/// ```
public final class Initializers {

    /// A safe description of one source or decoding failure.
    ///
    /// The record contains the full setting pointer, source identity, decoder
    /// name, and one stable reason. It never contains source text, decoded
    /// values, exception text, or secret bytes.
    ///
    /// Inspect a failure by reading its stable fields. Do not try to recover
    /// source text from this record. The source handler deliberately discards
    /// that text before it calls the consumer.
    ///
    /// ```
    /// var failures = new java.util.concurrent.CopyOnWriteArrayList<Initializers.Failure>();
    /// var source = Initializers.environment(System::getenv, failures::add);
    /// source.run(application.Effect.of(Settings.RESOLVE)
    ///         .with("pointer", "/images/maximumBytes")
    ///         .with("source", "environment:IMAGES_MAX_BYTES")
    ///         .with("decoder", "number")
    ///         .with("condition", json.Json.Object.of("kind", "missing")),
    ///         message -> {});
    /// if (!failures.isEmpty()) {
    ///     var failure = failures.getFirst();
    ///     System.err.println(failure.pointer() + " failed: " + failure.reason());
    /// }
    /// ```
    public record Failure(String pointer, String scheme, String name, String decoder, String reason) {
        /// Returns the full settings pointer that failed.
        @Override public String pointer() { return pointer; }

        /// Returns the source scheme that failed.
        @Override public String scheme() { return scheme; }

        /// Returns the source name without its scheme prefix.
        @Override public String name() { return name; }

        /// Returns the decoder name selected for the source.
        @Override public String decoder() { return decoder; }

        /// Returns `unreadable`, `too-large`, `invalid-value`, or `unknown-scheme`.
        @Override public String reason() { return reason; }
    }

    /// Handles `settings.resolve` effects for one source scheme.
    public interface Source extends Effect.Handler {
        /// Returns the non-empty scheme handled by this source.
        String scheme();
    }

    private static final long MAX = 1024L * 1024L;

    private Initializers() {}

    /// Associates a custom effect handler with one source scheme.
    ///
    /// The scheme must be non-empty and contain no colon. The wrapper returns
    /// the handler's emissions unchanged. The custom handler must enforce the
    /// same one-result and secret-safety rules as the standard handlers.
    public static Source source(String scheme, Effect.Handler handler) {
        if (scheme == null || scheme.isEmpty() || scheme.indexOf(':') >= 0) {
            throw new IllegalArgumentException("an initializer scheme must be non-empty and contain no colon");
        }
        Objects.requireNonNull(handler, "handler");
        return new Source() {
            @Override
            public String scheme() {
                return scheme;
            }

            @Override
            public void run(Effect effect, Effect.Emitter emit) throws Exception {
                handler.run(effect, emit);
            }
        };
    }

    /// Creates an environment source with a failure consumer that ignores failures.
    ///
    /// The function returns null for a missing variable. A successful decode
    /// emits one `initialize` message. A failed decode emits nothing.
    public static Source environment(Function<String, String> read) {
        return environment(read, ignored -> {});
    }

    /// Creates an environment source with an explicit failure consumer.
    ///
    /// The function is called with the source name from each matching effect.
    /// A null result means that the source is missing. A decoder failure calls
    /// `failures` with `invalid-value` and emits no message. A source failure
    /// calls it with `unreadable` and emits no message.
    public static Source environment(Function<String, String> read, Consumer<Failure> failures) {
        Objects.requireNonNull(read, "read");
        Objects.requireNonNull(failures, "failures");
        return new Source() {
            @Override
            public String scheme() {
                return "environment";
            }

            @Override
            public void run(Effect effect, Effect.Emitter emit) {
                var request = request(effect);
                if (!scheme().equals(request.scheme())) return;
                try {
                    var text = read.apply(request.name());
                    if (text == null) return;
                    decode(request, text, emit, failures);
                } catch (RuntimeException unreadable) {
                    failures.accept(new Failure(request.pointer(), scheme(), request.name(), request.decoder(), "unreadable"));
                }
            }
        };
    }

    /// Creates a file source with a failure consumer that ignores failures.
    ///
    /// The handler reads only files whose normalized lexical path starts under
    /// `root`. A missing file emits nothing. A file larger than one mebibyte is
    /// rejected before its contents are decoded.
    public static Source files(Path root) {
        return files(root, ignored -> {});
    }

    /// Creates a file source with an explicit failure consumer.
    ///
    /// The handler follows symbolic links. It reports unreadable files as
    /// `unreadable` and oversized files as `too-large`. It emits one
    /// `initialize` message only after decoding succeeds.
    public static Source files(Path root, Consumer<Failure> failures) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(failures, "failures");
        var base = root.toAbsolutePath().normalize();
        return new Source() {
            @Override
            public String scheme() {
                return "file";
            }

            @Override
            public void run(Effect effect, Effect.Emitter emit) {
                var request = request(effect);
                if (!scheme().equals(request.scheme())) return;
                var path = base.resolve(request.name()).normalize();
                if (!path.startsWith(base)) {
                    failures.accept(new Failure(request.pointer(), scheme(), request.name(), request.decoder(), "unreadable"));
                    return;
                }
                try {
                    if (Files.notExists(path)) return;
                    if (Files.size(path) > MAX) {
                        failures.accept(new Failure(request.pointer(), scheme(), request.name(), request.decoder(), "too-large"));
                        return;
                    }
                    var bytes = Files.readAllBytes(path);
                    if (bytes.length > MAX) {
                        failures.accept(new Failure(request.pointer(), scheme(), request.name(), request.decoder(), "too-large"));
                        return;
                    }
                    decode(request, new String(bytes, StandardCharsets.UTF_8), emit, failures);
                } catch (IOException | SecurityException failure) {
                    failures.accept(new Failure(request.pointer(), scheme(), request.name(), request.decoder(), "unreadable"));
                }
            }
        };
    }

    /// Combines source handlers and ignores dispatcher failures.
    ///
    /// The returned handler selects a source by the scheme before the first
    /// colon in the effect's `source` descriptor. Duplicate schemes fail while
    /// this method builds the handler. An unknown scheme emits no message.
    public static Effect.Handler sources(Source... sources) {
        return sources(ignored -> {}, sources);
    }

    /// Combines source handlers and reports dispatcher failures to `failures`.
    ///
    /// The returned handler delegates a matching effect to exactly one source.
    /// An unknown scheme calls `failures` with `unknown-scheme` and emits no
    /// message. Duplicate schemes fail while this method builds the handler.
    public static Effect.Handler sources(Consumer<Failure> failures, Source... sources) {
        Objects.requireNonNull(failures, "failures");
        var byScheme = new LinkedHashMap<String, Source>();
        for (var source : sources) {
            Objects.requireNonNull(source, "source");
            if (byScheme.putIfAbsent(source.scheme(), source) != null) {
                throw new IllegalArgumentException("duplicate initializer scheme: " + source.scheme());
            }
        }
        return (effect, emit) -> {
            var request = request(effect);
            var source = byScheme.get(request.scheme());
            if (source == null) {
                failures.accept(new Failure(request.pointer(), request.scheme(), request.name(), request.decoder(), "unknown-scheme"));
                return;
            }
            source.run(effect, emit);
        };
    }

    private static void decode(Request request, String text, Effect.Emitter emit, Consumer<Failure> failures) {
        try {
            var value = request.decoderObject().decode(text);
            if (size(value) > MAX) {
                failures.accept(new Failure(request.pointer(), request.scheme(), request.name(), request.decoder(), "too-large"));
                return;
            }
            emit.emit(Message.of("initialize")
                    .with("pointer", request.pointer())
                    .with("condition", request.condition())
                    .with("value", value));
        } catch (Exception invalid) {
            failures.accept(new Failure(request.pointer(), request.scheme(), request.name(), request.decoder(), "invalid-value"));
        }
    }

    private static long size(Json value) {
        var counter = new CountingWriter();
        try {
            value.write(counter);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return counter.bytes;
    }

    private static Request request(Effect effect) {
        var source = effect.string("source", "");
        var colon = source.indexOf(':');
        var scheme = colon < 0 ? source : source.substring(0, colon);
        var name = colon < 0 ? "" : source.substring(colon + 1);
        var decoderName = effect.string("decoder", "");
        return new Request(effect.string("pointer", ""), scheme, name,
                decoderName, effect.get("condition"), decoder(decoderName));
    }

    private static Initial.Decoder decoder(String name) {
        return switch (name) {
            case "string" -> Initial.string();
            case "number" -> Initial.number();
            case "boolean" -> Initial.booleanValue();
            case "json" -> Initial.json();
            default -> null;
        };
    }

    private record Request(String pointer, String scheme, String name, String decoder,
                           Json condition, Initial.Decoder decoderObject) {}

    private static final class CountingWriter extends java.io.Writer {
        private long bytes;

        @Override
        public void write(char[] value, int offset, int length) {
            bytes += new String(value, offset, length).getBytes(StandardCharsets.UTF_8).length;
        }

        @Override
        public void write(String value, int offset, int length) {
            bytes += value.substring(offset, offset + length).getBytes(StandardCharsets.UTF_8).length;
        }

        @Override
        public void write(int value) {
            bytes += String.valueOf((char) value).getBytes(StandardCharsets.UTF_8).length;
        }

        @Override public void flush() {}
        @Override public void close() {}
    }
}
