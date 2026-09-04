package reload;

/// A process-local key for one typed value carried by a [Generation].
///
/// Call [#create] in host code and pass the same key to candidate generations.
/// Two keys remain distinct when they have the same value type.
public final class Capability<T> {

    private Capability() {}

    /// A new identity key whose values have type `T`.
    public static <T> Capability<T> create() {
        return new Capability<>();
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
