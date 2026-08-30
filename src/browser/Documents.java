package browser;

import application.Effect;
import application.Message;
import application.Step;
import java.util.List;
import json.Json;
import symbols.Catalog;

/// The state and effects for one package-document request.
public final class Documents {

    public static final String LOOK = "documents.look";
    public static final String FOUND = "documents.found";
    public static final String MISSING = "documents.missing";

    private Documents() {}

    public record State(String packageName, String kind, String slug, Json.Object description, String problem) {

        public static State nothing() {
            return new State("", "", "", Json.Object.of(), "");
        }

        public boolean found() {
            return !description.fields().isEmpty();
        }

        State asking(String packageName, String kind, String slug) {
            return new State(packageName, kind, slug, Json.Object.of(), "");
        }

        State describing(Json.Object description) {
            return new State(packageName, kind, slug, description, "");
        }

        State failed(String problem) {
            return new State(packageName, kind, slug, Json.Object.of(), problem);
        }
    }

    public static Step<State> asked(State state, Message message) {
        var params = message.body().get("params") instanceof Json.Object object ? object : Json.Object.of();
        var packageName = params.string("name", "");
        var kind = params.string("kind", "");
        var slug = params.string("slug", "");
        if (packageName.isBlank() || !List.of("tutorial", "howto", "reference", "guide").contains(kind)) {
            return Step.of(state.failed("no document was named"));
        }
        return Step.of(state.asking(packageName, kind, slug), Effect.of(LOOK)
                .with("package", packageName).with("kind", kind).with("slug", slug));
    }

    public static Step<State> found(State state, Message message) {
        return Step.of(state.describing(message.body()));
    }

    public static Step<State> missing(State state, Message message) {
        return Step.of(state.failed("no document called " + message.string("document", "")));
    }

    public static Step<State> failed(State state, Message message) {
        return Step.of(state.failed(message.string("reason", "the document could not be read")));
    }

    public static Effect.Handler looking(Catalog index) {
        return (effect, emit) -> {
            var packageName = effect.string("package", "");
            var kind = effect.string("kind", "");
            var slug = effect.string("slug", "");
            if (!index.ready()) {
                emit.emit(Message.error("documentation is being indexed"));
                return;
            }
            var page = index.documentPage(packageName, kind, slug);
            var all = page.documents();
            var documents = all.stream().filter(document -> document.kind().equals(kind)).toList();
            var selected = page.selected();
            if (selected.isEmpty() && (!slug.isEmpty() || documents.isEmpty())) {
                emit.emit(Message.of(MISSING).with("document", path(packageName, kind, slug)));
                return;
            }
            var listed = Json.Array.of(documents.stream()
                    .map(document -> (Json) document.describe().without("doc")
                            .with("file", java.nio.file.Path.of(document.source()).getFileName().toString())
                            .without("source"))
                    .toList());
            var links = Json.Array.of(all.stream()
                    .map(document -> (Json) document.describe().without("doc")
                            .with("file", java.nio.file.Path.of(document.source()).getFileName().toString())
                            .without("source"))
                    .toList());
            var description = selected.map(document -> document.describe().with("documents", listed).with("links", links))
                    .orElseGet(() -> Json.Object.of()
                            .with("package", packageName).with("kind", kind).with("slug", "")
                            .with("title", title(kind)).with("documents", listed).with("links", links));
            emit.emit(Message.of(FOUND, description));
        };
    }

    private static String path(String packageName, String kind, String slug) {
        return packageName + "/" + kind + (slug.isEmpty() ? "" : "/" + slug);
    }

    private static String title(String kind) {
        return kind.equals("howto") ? "How-to" : Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
    }
}
