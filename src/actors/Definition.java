package actors;

import application.Application;
import java.util.stream.Stream;
import json.Json;

/// A definition says what one type of actor is: how to build a fresh instance,
/// and how to show its state to a person.
///
/// ## How an instance is built
///
/// [ActorSystem] calls [#instantiate(Address)] every time it summons an actor of
/// this type, and then replays that actor's log through the application it
/// received. The state a caller sees is therefore always the fold of the
/// recorded commands through the definition that is registered *now*, never a
/// stored state from an earlier version of the code. Changing an update
/// function and evicting the actor is the whole upgrade procedure. There is no
/// migration, because there is nothing stored to migrate.
///
/// ## What belongs here and what does not
///
/// A definition registers update functions. It does not register effect
/// handlers. Handlers are given to [ActorSystem#effect(String, Effect.Handler)] once,
/// at startup, because a handler owns a connection, a file or a socket, and
/// those must not be rebuilt every time an actor is summoned. The split also
/// keeps a definition pure: it maps a state and a message to a state and a list
/// of effects, and it can be tested with no system at all.
///
/// ## Why `inspect` returns JSON
///
/// The state type `S` never leaves the system. [ActorSystem#inspect(Address)] hands
/// back JSON, and this method is where a definition decides what that JSON
/// looks like. Letting a caller hold an `S` would mean a caller holding a class
/// that a future reload wants to discard, and the default is a string form
/// rather than a reflective dump because a definition knows what is worth
/// showing and a framework does not.
public interface Definition<S> {

    /// The name of this type of actor. It becomes the `type` part of every
    /// address of an instance and it must not contain a slash or a colon.
    String type();

    /// A fresh application for one instance, with its initial state and its
    /// update functions. The address is passed so that an actor can address
    /// its own children without being told who it is.
    Application<S> instantiate(Address self);

    /// This state as JSON, for an inspector or a person at a terminal.
    default Json inspect(S state) {
        return Json.of(String.valueOf(state));
    }

    /// Whether this actor has, as today's rules see it, nothing further to do.
    ///
    /// **This is a hint and nothing may depend on it.** The system uses it to
    /// evict a settled actor sooner than its idle timeout would, and the
    /// registry reports it so that an operator can see what is cold. Nothing
    /// about correctness changes either way, and it must stay that way, for a
    /// reason worth stating plainly:
    ///
    /// A durable actor is always summonable again. "Settled" is an opinion held
    /// by the definition that is registered now, and a later definition may
    /// disagree — a returns policy that lets a closed basket be amended turns a
    /// settled basket back into a working one, from the same commands. Treating
    /// this as a fact, by refusing messages or by deleting a log, would make
    /// that change impossible.
    ///
    /// A settled actor still accepts messages. It is evicted, not closed.
    ///
    /// The system calls this on the actor's own thread, straight after a message
    /// is handled, and remembers the answer. It is never called from a sweeper
    /// or an inspector, so an implementation does not have to be thread-safe. An
    /// implementation that throws is treated as answering false.
    default boolean settled(S state) {
        return false;
    }

    /// The address of one instance of this type, in the local system. This
    /// exists so that no caller ever writes the type name as a string.
    default Address at(String id) {
        return Address.of(type(), id);
    }

    /// The addresses of many instances, which is what a [Fleet] fans out over.
    default Stream<Address> over(Stream<String> ids) {
        return ids.map(this::at);
    }
}
