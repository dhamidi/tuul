package actors;

/// The immediate result of [ActorSystem#tell(Address, application.Message)].
///
/// `accepted` means that the runtime accepted the message for delivery. It does
/// not mean that the destination processed the message.
public enum DeliveryStatus {
    accepted,
    busy,
    expired,
    /// The actor does not declare this message type.
    unsupported,
    /// The message payload does not satisfy its declared JSON Schema.
    invalid,
    unknown,
    quarantined,
    unreachable,
    closed
}
