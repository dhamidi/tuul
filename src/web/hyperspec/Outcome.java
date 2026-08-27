package web.hyperspec;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;

/// What running a spec found.
///
/// A failure carries what was expected, what the page offered instead, and the
/// line it was asked on. The second of those is the one that saves an
/// afternoon: a spec fails because the page changed, and the useful thing to
/// print is not the page but what a client could have done with it.
public record Outcome(List<Check> checks, List<Failure> failures) {

    public record Check(String client, int line, String what) {}

    public record Failure(String client, int line, String what, String found) {

        public String describe() {
            return client + " line " + line + ": " + what + (found.isEmpty() ? "" : " — " + found);
        }
    }

    public Outcome {
        checks = List.copyOf(checks);
        failures = List.copyOf(failures);
    }

    public boolean ok() {
        return failures.isEmpty();
    }

    public int passed() {
        return checks.size();
    }

    public void report(Writer out) throws IOException {
        for (var check : checks) out.write("ok   " + check.client() + " " + check.what() + "\n");
        for (var failure : failures) out.write("FAIL " + failure.describe() + "\n");
        out.write(passed() + " expectations met" + (ok() ? "" : ", " + failures.size() + " not") + "\n");
        out.flush();
    }

    public String report() {
        var out = new StringWriter();
        try {
            report(out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toString();
    }
}
