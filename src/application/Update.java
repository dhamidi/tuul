package application;

/// Takes a message and a state, returns the next state and the effects to
/// apply. Anything with that shape is an application: a lambda, a method
/// reference, or a class that wants fields of its own.
@FunctionalInterface
public interface Update<S> {

    Step<S> update(S state, Message message);
}
