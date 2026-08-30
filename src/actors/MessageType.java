package actors;

import application.Message;
import java.util.Objects;
import java.util.Optional;
import json.Json;
import jsonschema.Output;
import jsonschema.Schema;
import jsonschema.Store;

/// One imperative message type that an actor accepts.
///
/// A command can change state and the actor writes it to the journal before it
/// runs. A query cannot change state and the actor does not write it to the
/// journal. [Behavior#on(MessageType, application.Update)] enforces the state
/// rule for a query.
///
/// Call [#command(String)] or [#query(String)] once and keep the result as a
/// constant. Use [#message()] to send that type without repeating its name.
/// The name must be an imperative verb phrase, such as `add-item`,
/// `record-payment`, or `get-total`.
///
/// A declaration can carry a JSON Schema for the message payload. A JSON value
/// compiles when the declaration is built. A caller can instead supply a
/// [Schema] compiled from its own [Store]. [#validate(Message)] validates only
/// [application.Envelope#body()]. The message type and delivery envelope are
/// outside the payload and are not schema input.
public final class MessageType {

    /// Whether the actor records this message before it handles it.
    public enum Kind {
        /// The actor records the message. Its handler can change state.
        command,
        /// The actor does not record the message. Its handler cannot change
        /// state.
        query
    }

    private final String type;
    private final Kind kind;
    private final Schema schema;

    private MessageType(String type, Kind kind, Schema schema) {
        this.type = imperative(type);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.schema = schema;
    }

    /// Declares a journaled command with no payload schema.
    public static MessageType command(String imperative) {
        return new MessageType(imperative, Kind.command, null);
    }

    /// Declares a journaled command whose payload must satisfy `schema`.
    public static MessageType command(String imperative, Json schema) {
        return command(imperative, Store.of().compile(Objects.requireNonNull(schema, "schema")));
    }

    /// Declares a journaled command with a schema compiled by the caller.
    /// Use this overload for schemas with registered resources, vocabularies,
    /// or formats.
    public static MessageType command(String imperative, Schema schema) {
        return new MessageType(imperative, Kind.command, Objects.requireNonNull(schema, "schema"));
    }

    /// Declares a non-journaled query with no payload schema.
    public static MessageType query(String imperative) {
        return new MessageType(imperative, Kind.query, null);
    }

    /// Declares a non-journaled query whose payload must satisfy `schema`.
    public static MessageType query(String imperative, Json schema) {
        return query(imperative, Store.of().compile(Objects.requireNonNull(schema, "schema")));
    }

    /// Declares a non-journaled query with a schema compiled by the caller.
    /// Use this overload for schemas with registered resources, vocabularies,
    /// or formats.
    public static MessageType query(String imperative, Schema schema) {
        return new MessageType(imperative, Kind.query, Objects.requireNonNull(schema, "schema"));
    }

    /// The imperative verb phrase carried in [application.Envelope#TYPE].
    public String type() {
        return type;
    }

    /// Whether this declaration is a command or a query.
    public Kind kind() {
        return kind;
    }

    /// The compiled JSON Schema, or empty when every payload is valid.
    public Optional<Schema> schema() {
        return Optional.ofNullable(schema);
    }

    /// A message of this type with an empty payload.
    public Message message() {
        return Message.of(type);
    }

    /// A message of this type with `payload` as its payload.
    public Message message(Json.Object payload) {
        return Message.of(type, payload);
    }

    /// Validates a message payload. A declaration with no schema always
    /// returns a valid result.
    ///
    /// This method does not compare [application.Envelope#type()] with
    /// [#type()]. The actor selects this declaration by that type before it
    /// calls this method.
    public Output validate(Message message) {
        Objects.requireNonNull(message, "message");
        return schema == null ? Output.ok() : schema.validate(message.body());
    }

    private static String imperative(String type) {
        Objects.requireNonNull(type, "imperative");
        if (type.isBlank()) throw new IllegalArgumentException("an actor message type cannot be blank");
        if (!type.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException(
                    "an actor message type must be a lower-case imperative verb phrase: " + type);
        }
        return type;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof MessageType that)) return false;
        return type.equals(that.type) && kind == that.kind && Objects.equals(schema, that.schema);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, kind, schema);
    }

    @Override
    public String toString() {
        return kind + " " + type;
    }
}
