package selftest;

import java.nio.file.Path;
import java.util.List;

/// What a self-test found, and where the evidence is.
public record Report(Path directory, boolean kept, List<Check> checks) {

    /// One assertion about tuul's behaviour. `detail` is what was actually seen,
    /// kept for the ones that failed.
    public record Check(String what, boolean ok, String detail) {}

    public long passed() {
        return checks.stream().filter(Check::ok).count();
    }

    public long failed() {
        return checks.size() - passed();
    }

    public boolean ok() {
        return failed() == 0;
    }
}
