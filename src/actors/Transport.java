package actors;

import application.Message;

/// How a message reaches a system that is not this one.
///
/// ## What this package does not know
///
/// A transport is the only thing `actors` knows about the world outside its own
/// process. Sockets, framing, retries, authentication and whether the other
/// system is even running all live behind this interface. The package ships one
/// implementation, [actors.transport.Loopback], which joins two systems inside
/// one process so that the tests can exercise remote addressing without a
/// network. Anything real — JSON-RPC over a Unix socket, an HTTP body, a
/// message queue — is written outside this package against this interface.
///
/// ## It needs a reply path
///
/// A one-way `deliver` is not enough. An ask puts a reply address in the
/// delivery, that address names the asking system, and the answer has to find
/// its way back. A transport that can only push messages in one direction
/// cannot carry an ask, and an implementation that has no return channel must
/// say so by refusing the delivery rather than by dropping the reply in
/// silence.
///
/// ## Failure is reported, not thrown away
///
/// An implementation that cannot deliver throws. [ActorSystem] turns that into an
/// `handle-delivery-error` message with cause
/// [Undeliverable.Cause#unreachable] and hands it to the sender, so a failed
/// remote delivery is handled by an update function like any other fact.
public interface Transport extends AutoCloseable {

    /// Hands one message to the system named by `to`.
    ///
    /// @param to      the address, whose system is not the one calling
    /// @param message the message to deliver
    /// @param replyTo where an answer should be sent, or null when none is
    ///                expected. An implementation that cannot carry a reply
    ///                must throw when this is not null.
    void deliver(Address to, Message message, Address replyTo) throws Exception;

    @Override
    default void close() {}
}
