package browser;

import application.Effect;
import application.Message;
import application.Step;
import java.util.List;
import json.Json;
import json.Pointer;
import symbols.Catalog;
import symbols.Document;
import symbols.Questions;

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
        var packageName = parameter(message, "name");
        var kind = parameter(message, "kind");
        var slug = parameter(message, "slug");
        if (packageName.isBlank() || !Document.KINDS.contains(kind)) {
            return Step.of(state.failed("no document was named"));
        }
        return Step.of(state.asking(packageName, kind, slug), Effect.of(LOOK)
                .with("package", packageName).with("kind", kind).with("slug", slug));
    }

    private static String parameter(Message message, String name) {
        return message.body().find(Pointer.ofTokens("params", name)).map(value -> value.string("")).orElse("");
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
            emit.emit(Questions.document(index, new Document.Reference(packageName, kind, slug))
                    .map(description -> Message.of(FOUND, description))
                    .orElseGet(() -> Message.of(MISSING).with("document", path(packageName, kind, slug))));
        };
    }

    private static String path(String packageName, String kind, String slug) {
        return packageName + "/" + kind + (slug.isEmpty() ? "" : "/" + slug);
    }
}
