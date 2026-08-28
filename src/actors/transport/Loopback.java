package actors.transport;

import actors.Address;
import actors.ActorSystem;
import actors.Transport;
import application.Message;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// A transport that joins systems inside one process.
///
/// Two systems register with the same loopback, and a message addressed to the
/// other system's name arrives there directly. Nothing is serialised, nothing
/// is framed, and no port is bound.
///
/// This exists so that remote addressing can be tested without a network. A
/// test that binds a port can fail because the port was busy, and a test that
/// serialises can fail for reasons that have nothing to do with the actors. It
/// also shows how little a transport has to do: name a system, hand it a
/// message, and carry the reply address along so that an ask can be answered.
///
/// A real transport does the same two things with a socket in the middle, and
/// it has to serialise the reply address so that the answer knows where to go.
/// A message and an address are both JSON already, so there is nothing else to
/// invent.
public final class Loopback implements Transport {

    private final Map<String, ActorSystem> systems = new ConcurrentHashMap<>();

    public static Loopback of(ActorSystem... systems) {
        var loopback = new Loopback();
        for (var system : systems) loopback.join(system);
        return loopback;
    }

    /// Adds a system, and makes this the transport that system uses for
    /// addresses that are not its own.
    public Loopback join(ActorSystem system) {
        systems.put(system.name(), system);
        system.transport(this);
        return this;
    }

    @Override
    public void deliver(Address to, Message message, Address replyTo) {
        var system = systems.get(to.system());
        if (system == null) throw new IllegalStateException("no system named " + to.system());
        system.receive(to, message, replyTo);
    }
}
