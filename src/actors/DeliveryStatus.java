package actors;

/// The immediate result of [ActorSystem#tell(Address, application.Message)].
///
/// `accepted` means that the runtime accepted the message for delivery. It does
/// not mean that the destination processed the message.
public enum DeliveryStatus {
    accepted,
    busy,
    expired,
    unknown,
    quarantined,
    unreachable,
    closed
}
