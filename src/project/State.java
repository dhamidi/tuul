package project;

import java.nio.file.Path;
import java.util.List;

/// What the project application knows: where the project is, what it was asked
/// to do, and how it turned out.
public record State(Path directory, Action action, String entrypoint, List<String> arguments, int exit) {

    /// What a build is *for* — the same compile serves all three, and the
    /// answer decides what happens once it succeeds.
    public enum Action {
        NONE, NATIVE, BUILD, RUN, TEST
    }

    public static State of(Path directory) {
        return new State(directory, Action.NONE, "", List.of(), 0);
    }

    public State doing(Action action) {
        return new State(directory, action, entrypoint, arguments, exit);
    }

    public State running(String entrypoint, List<String> arguments) {
        return new State(directory, Action.RUN, entrypoint, arguments, exit);
    }

    public State exited(int status) {
        return new State(directory, action, entrypoint, arguments, status);
    }

    public State failed() {
        return exited(1);
    }
}
