package reload;

import application.Application;
import json.Json;
import java.util.Objects;

/// Defines one named long-lived application and its state migration functions.
///
/// Use [#of(String, String, Initial)] when a new generation can start with
/// initial state. Add [#withTransfer(Snapshot, Transfer)] when the definition
/// uses [StatePolicy#TRANSFER]. The application owns its effect handlers, so
/// every effect emitted by one turn stays with that turn's definition.
public final class ApplicationDefinition<S> {

    private final String name;
    private final String version;
    private final Initial<S> initial;
    private final Snapshot<S> snapshot;
    private final Transfer<S> transfer;

    private ApplicationDefinition(String name, String version, Initial<S> initial,
            Snapshot<S> snapshot, Transfer<S> transfer) {
        this.name = require(name, "name");
        this.version = require(version, "version");
        this.initial = Objects.requireNonNull(initial, "initial");
        this.snapshot = snapshot;
        this.transfer = transfer;
    }

    /// Creates a definition that starts each new instance from `initial`.
    public static <S> ApplicationDefinition<S> of(String name, String version,
            Initial<S> initial) {
        return new ApplicationDefinition<>(name, version, initial, null, null);
    }

    /// Returns the stable name used by [Applications#dispatch(String, application.Message...)]
    /// to select this application. The name must not be blank.
    public String name() { return name; }

    /// Returns the version passed to [Transfer] during a state migration.
    /// Changing this value identifies the state schema of a new definition.
    public String version() { return version; }

    Application<S> create() throws Exception {
        return Objects.requireNonNull(initial.create(), "initial returned null");
    }

    /// Adds versioned JSON state migration functions for [StatePolicy#TRANSFER].
    /// The snapshot receives the current application. The transfer receives the
    /// prior version and the snapshot object, then creates the replacement.
    public ApplicationDefinition<S> withTransfer(Snapshot<S> snapshot, Transfer<S> transfer) {
        return new ApplicationDefinition<>(name, version,
                initial, Objects.requireNonNull(snapshot, "snapshot"),
                Objects.requireNonNull(transfer, "transfer"));
    }

    Json.Object snapshot(Application<S> application) throws Exception {
        if (snapshot == null) throw new IllegalStateException(
                "application " + name + " has no state snapshot function");
        return Objects.requireNonNull(snapshot.write(application.state()),
                "snapshot returned null");
    }

    @SuppressWarnings("unchecked")
    Json.Object snapshotUntyped(Application<?> application) throws Exception {
        return snapshot((Application<S>) application);
    }

    Application<S> transfer(String priorVersion, Json.Object state) throws Exception {
        if (transfer == null) throw new IllegalStateException(
                "application " + name + " has no state transfer function");
        return Objects.requireNonNull(transfer.create(priorVersion, state),
                "transfer returned null");
    }

    /// Creates the initial application state for a definition.
    @FunctionalInterface
    public interface Initial<S> {
        Application<S> create() throws Exception;
    }

    /// Serializes one application state for a versioned transfer.
    @FunctionalInterface
    public interface Snapshot<S> {
        Json.Object write(S state) throws Exception;
    }

    /// Creates the replacement application from a prior schema version and JSON state.
    @FunctionalInterface
    public interface Transfer<S> {
        Application<S> create(String priorVersion, Json.Object state) throws Exception;
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
