package actors;

import application.Application;
import application.Message;
import application.Step;
import application.Update;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// One actor instance's state and its declared message handlers.
///
/// Call [#of(Object)] with the initial state. Call
/// [#on(MessageType, Update)] once for each imperative command or query that
/// the actor accepts. [Definition#instantiate(Address)] returns the completed
/// behavior.
///
/// ```
/// static final MessageType ADD_ITEM = MessageType.command("add-item");
/// static final MessageType GET_TOTAL = MessageType.query("get-total");
///
/// return Behavior.of(new Basket())
///         .on(ADD_ITEM, Basket::add)
///         .on(GET_TOTAL, Basket::answerTotal);
/// ```
///
/// Commands commit the state their handlers return. Queries discard the state
/// their handlers return and keep the state from before the query. Query
/// effects still run, so a query can reply to its caller.
///
/// Every behavior also accepts `handle-error`, `handle-timeout`, and
/// `handle-delivery-error`. Register a handler with the matching declaration
/// to act on one. [#messageTypes()] includes these runtime commands.
public final class Behavior<S> {

    private final Application<S> application;
    private final Map<String, MessageType> messages = new LinkedHashMap<>();

    private Behavior(S initial) {
        application = Application.of(initial);
        accept(MessageType.command(Message.HANDLE_ERROR));
        accept(MessageType.command(Message.HANDLE_TIMEOUT));
        accept(Undeliverable.MESSAGE);
    }

    /// Starts a behavior with `initial` as its state.
    public static <S> Behavior<S> of(S initial) {
        return new Behavior<>(initial);
    }

    /// Registers one handler for a declared message type.
    ///
    /// Registering the same declaration again runs both handlers in
    /// registration order. Registering a different declaration with the same
    /// type throws. A type cannot be a command in one place and a query in
    /// another.
    public Behavior<S> on(MessageType message, Update<S> update) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(update, "update");
        var existing = messages.putIfAbsent(message.type(), message);
        if (existing != null && !existing.equals(message)) {
            throw new IllegalArgumentException("conflicting declarations for actor message " + message.type());
        }
        application.on(message.type(), message.kind() == MessageType.Kind.command
                ? update
                : (state, query) -> effectsOnly(state, update.update(state, query)));
        return this;
    }

    private void accept(MessageType message) {
        messages.put(message.type(), message);
    }

    /// Registers what the actor does after replay and before its first live
    /// message. The runtime sends this control message once per summon. The
    /// actor does not journal it.
    public Behavior<S> resuming(Update<S> update) {
        application.on(Actor.RESUME, update);
        return this;
    }

    /// The runtime commands and the behavior's command and query declarations,
    /// in registration order. The returned list is a snapshot.
    public List<MessageType> messageTypes() {
        return List.copyOf(messages.values());
    }

    MessageType messageType(String type) {
        return messages.get(type);
    }

    Application<S> application() {
        return application;
    }

    S state() {
        return application.state();
    }

    Step<S> advance(Message message) {
        return application.advance(message);
    }

    private static <S> Step<S> effectsOnly(S state, Step<S> query) {
        return new Step<>(state, query.effects());
    }
}
