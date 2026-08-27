package web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import application.Application;
import application.Effect;
import application.Update;

/// A handler written the way tuul writes everything else: a request becomes a
/// message, an update returns a state and the effects it wants, and the state is
/// rendered.
///
/// The state is per request, which is the whole difference between a web
/// application and the ones that run in a terminal. A page is about one thing
/// somebody asked for; the server's own long-lived state — who is connected,
/// what is cached — belongs to an application that outlives the request and is
/// reached from an effect.
///
/// ```
/// var page = Page.of(Symbol::nothing)
///         .on("symbol", Symbols::look)
///         .effect("symbols.lookup", Symbols::find)
///         .render((state, request, response) -> Responses.html(Views.symbol(state), response));
/// ```
///
/// Failure is the runtime's, not the handler's: an update that throws leaves the
/// state alone and turns into an `error` message, so a page renders what it has
/// rather than a stack trace. That is the same promise `application` makes
/// everywhere else, and it is worth more here than anywhere.
public final class Page<S> implements Handler {

    /// What turns the state an update left into a response.
    @FunctionalInterface
    public interface Render<S> {
        void render(S state, Request request, Response response) throws Exception;
    }

    private final Supplier<S> initial;
    private final Map<String, Update<S>> updates;
    private final Map<String, Effect.Handler> effects;
    private final Render<S> render;

    private Page(Supplier<S> initial, Map<String, Update<S>> updates, Map<String, Effect.Handler> effects,
                 Render<S> render) {
        this.initial = initial;
        this.updates = Map.copyOf(updates);
        this.effects = Map.copyOf(effects);
        this.render = render;
    }

    /// A page whose state starts fresh for every request. The supplier is called
    /// once per request, so nothing a handler does can leak into the next one.
    public static <S> Page<S> of(Supplier<S> initial) {
        return new Page<>(initial, Map.of(), Map.of(),
                (state, request, response) -> Responses.text(String.valueOf(state), response));
    }

    public Page<S> on(String type, Update<S> update) {
        var next = new LinkedHashMap<>(updates);
        next.put(type, update);
        return new Page<>(initial, next, effects, render);
    }

    public Page<S> effect(String type, Effect.Handler handler) {
        var next = new LinkedHashMap<>(effects);
        next.put(type, handler);
        return new Page<>(initial, updates, next, render);
    }

    public Page<S> render(Render<S> render) {
        return new Page<>(initial, updates, effects, render);
    }

    @Override
    public void handle(Request request, Response response) throws Exception {
        var application = Application.of(initial.get());
        updates.forEach(application::on);
        effects.forEach(application::effect);
        render.render(application.dispatch(Requests.message(request)), request, response);
    }
}
