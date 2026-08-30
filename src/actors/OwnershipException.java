package actors;

import java.nio.file.Path;

/// An actor could not be summoned because somebody else owns it.
///
/// This is deliberately not a `handle-delivery-error` notice. An undeliverable
/// message is a runtime condition that a sender can compensate for, and two
/// processes running one actor is a deployment fault that nobody downstream can
/// do anything about. It travels as an exception so that it reaches whoever
/// started the system rather than being absorbed by an actor's update function.
public final class OwnershipException extends RuntimeException {

    private final Address address;

    private OwnershipException(Address address, String message) {
        super(message);
        this.address = address;
    }

    static OwnershipException taken(Address address, Path lock) {
        return new OwnershipException(address, "another owner is running " + address
                + "; the lock at " + lock + " is held. Only one process may run one actor,"
                + " because two would interleave commands into one log.");
    }

    /// The actor that could not be claimed.
    public Address address() {
        return address;
    }
}
