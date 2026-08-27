import browser.BrowseSpec;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/// The hyperspecs, against a live application. `mise run spec`.
///
/// Separate from `run` because these need a server and because they are about
/// the application rather than about a library — and, today, because they fail:
/// they describe what `tuul browse` is supposed to do, and it does not do all
/// of it yet.

void main() throws Exception {
    var out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
    var failed = BrowseSpec.report(out);
    out.write(failed == 0 ? "every spec holds\n" : failed + " specs do not hold\n");
    out.flush();
    System.exit(failed == 0 ? 0 : 1);
}
