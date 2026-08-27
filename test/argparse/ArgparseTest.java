package argparse;

import harness.Check;
import java.io.IOException;
import java.io.StringWriter;

public final class ArgparseTest {

    private ArgparseTest() {}

    public static void run() throws IOException {
        flags();
        options();
        arguments();
        passthrough();
        subcommands();
        help();
        failures();
        usage();
    }

    private static void flags() {
        var command = Command.named("build", "compile it").flag("native", 'n', "the C as well");
        Check.equal("a flag that was given is true", "{\"native\":true}", values(command, "--native"));
        Check.equal("its short form is the same flag", "{\"native\":true}", values(command, "-n"));
        Check.equal("and one that was not is false, so nobody has to know what absent means",
                "{\"native\":false}", values(command));
    }

    private static void options() {
        var command = Command.named("docs", "describe a type")
                .value("class", 'c', "what to call it")
                .value("package", "where to put it", "generated")
                .repeated("source-path", "where to look");

        Check.equal("an option takes the word after it",
                "{\"class\":\"Api\",\"package\":\"generated\",\"source-path\":[]}",
                values(command, "--class", "Api"));
        Check.equal("or the word joined to it",
                "{\"class\":\"Api\",\"package\":\"generated\",\"source-path\":[]}",
                values(command, "--class=Api"));
        Check.equal("or comes after its short form",
                "{\"class\":\"Api\",\"package\":\"generated\",\"source-path\":[]}",
                values(command, "-c", "Api"));
        Check.equal("the last one given wins",
                "{\"class\":\"Second\",\"package\":\"generated\",\"source-path\":[]}",
                values(command, "--class", "First", "--class", "Second"));
        Check.equal("a declared default stands in for what was not said",
                "{\"package\":\"generated\",\"source-path\":[]}",
                values(command));
        Check.equal("a repeated option keeps every one of them, in order",
                "{\"package\":\"generated\",\"source-path\":[\"src\",\"lib\"]}",
                values(command, "--source-path", "src", "--source-path", "lib"));
    }

    private static void arguments() {
        var required = Command.named("new", "scaffold a project").argument("name", "what to call it");
        Check.equal("an argument is the first bare word", "{\"name\":\"invoicing\"}", values(required, "invoicing"));
        Check.equal("and options around it make no difference to which word that is",
                "{\"bare\":true,\"name\":\"invoicing\"}",
                values(required.flag("bare", "nothing at all"), "--bare", "invoicing"));

        var several = Command.named("copy", "copy things")
                .argument("from", "the source")
                .optional("to", "the target")
                .rest("also", "anything else");
        Check.equal("arguments fill up in the order they were declared",
                "{\"from\":\"a\",\"to\":\"b\",\"also\":[]}",
                values(several, "a", "b"));
        Check.equal("and a variadic one takes what is left",
                "{\"from\":\"a\",\"to\":\"b\",\"also\":[\"c\",\"d\"]}",
                values(several, "a", "b", "c", "d"));
        Check.equal("an optional one that was not given is simply not there",
                "{\"from\":\"a\",\"also\":[]}",
                values(several, "a"));
    }

    /// Everything after `--` belongs to whoever is being run, not to us.
    private static void passthrough() {
        var command = Command.named("run", "run an entrypoint")
                .flag("quiet", "say less")
                .optional("entrypoint", "which one")
                .passthrough("arguments", "what to pass on");

        Check.equal("what follows a bare dash-dash is taken as written",
                "{\"quiet\":false,\"entrypoint\":\"cli\",\"arguments\":[\"--json\",\"-x\",\"--\"]}",
                values(command, "cli", "--", "--json", "-x", "--"));
        Check.equal("and it does not have to be given anything",
                "{\"quiet\":false,\"arguments\":[]}",
                values(command));
        Check.equal("an option before it is still an option",
                "{\"quiet\":true,\"arguments\":[\"go\"]}",
                values(command, "--quiet", "--", "go"));
    }

    private static void subcommands() {
        var root = root();
        Check.equal("a subcommand is chosen by name", "{\"json\":false,\"symbol\":\"json.Json\"}",
                values(root, "docs", "json.Json"));
        Check.equal("and the path says which one was reached", "tuul docs", path(root, "docs", "json.Json"));
        Check.equal("a command with children and nothing said is not a failure", "{}", values(root));

        var deep = Command.named("tuul", "a toolchain")
                .command(Command.named("project", "project things")
                        .command(Command.named("new", "scaffold").argument("name", "what to call it")));
        Check.equal("subcommands nest", "tuul project new", path(deep, "project", "new", "invoicing"));
        Check.equal("and the innermost one takes the arguments",
                "{\"name\":\"invoicing\"}",
                values(deep, "project", "new", "invoicing"));
    }

    private static void help() {
        var root = root();
        Check.that("asking for help is not a failure", root.parse("--help") instanceof Parsed.Help);
        Check.that("the short form asks the same thing", root.parse("-h") instanceof Parsed.Help);
        Check.that("and a subcommand answers for itself", root.parse("docs", "--help") instanceof Parsed.Help);
        Check.equal("about the command that was asked about",
                "tuul docs",
                ((Parsed.Help) root.parse("docs", "--help")).path());
        Check.that("help is not one of the values a command gets",
                !values(root, "docs", "json.Json").contains("help"));
    }

    private static void failures() {
        var root = root();
        Check.equal("an unknown option is named", "unknown option: --nope", reason(root, "docs", "--nope"));
        Check.equal("an unknown command is named", "unknown command: frobnicate", reason(root, "frobnicate"));
        Check.equal("and a near miss is guessed at",
                "unknown command: dcos — did you mean docs?",
                reason(root, "dcos"));
        Check.equal("so is a near miss on an option",
                "unknown option: --jsn — did you mean --json?",
                reason(root, "docs", "--jsn"));
        Check.equal("an option that needs a value says so", "--out needs a value", reason(root, "build", "--out"));
        Check.that("rather than eating the next option",
                reason(root, "build", "--out", "--native").startsWith("unknown option"));

        var required = Command.named("new", "scaffold").argument("name", "what to call it");
        Check.equal("a missing argument is named", "missing <name>", reason(required));
        Check.equal("and one too many is too", "unexpected argument: extra", reason(required, "invoicing", "extra"));
        Check.that("a failure knows which command it was about",
                ((Parsed.Failure) root.parse("docs", "--nope")).path().equals("tuul docs"));
    }

    /// The help is the definition, read back — so a command that grows an
    /// option grows a line of help, and nobody has to remember to write it.
    private static void usage() throws IOException {
        var root = root();
        var written = help(root, "tuul");
        Check.that("the help names the program and what it is", written.startsWith("tuul — a toolchain"));
        Check.that("it lists the commands", written.contains("docs") && written.contains("build"));
        Check.that("with what each one is for", written.contains("describe a type"));
        Check.that("and says a command is expected", written.contains("usage:\n  tuul <command> [options]"));

        var docs = help(root.child("docs").orElseThrow(), "tuul docs");
        Check.that("a subcommand's help is about itself", docs.startsWith("tuul docs — describe a type"));
        Check.that("it shows the arguments it takes", docs.contains("usage:\n  tuul docs [options] [<symbol>]"));
        Check.that("every option it declares", docs.contains("--json"));
        Check.that("with the description it was declared with", docs.contains("print the description as JSON"));
        Check.that("and the help it did not have to declare", docs.contains("-h, --help"));

        var grown = help(root.child("docs").orElseThrow().flag("all", "include non-public members"), "tuul docs");
        Check.that("an option added to the definition appears in the help",
                grown.contains("--all") && grown.contains("include non-public members"));

        var value = help(Command.named("bind", "bind a library").value("class", "what to call it"), "tuul bind");
        Check.that("an option that takes a value says that it does", value.contains("--class <value>"));
    }

    private static Command root() {
        return Command.named("tuul", "a toolchain for modern Java")
                .command(Command.named("docs", "describe a type from the project")
                        .flag("json", "print the description as JSON")
                        .optional("symbol", "the type to describe"))
                .command(Command.named("build", "compile it")
                        .flag("native", 'n', "the C as well")
                        .value("out", "where to put it"));
    }

    private static String values(Command command, String... words) {
        return switch (command.parse(words)) {
            case Parsed.Values given -> given.values().text();
            case Parsed parsed -> "not values: " + parsed;
        };
    }

    private static String path(Command command, String... words) {
        return command.parse(words).path();
    }

    private static String reason(Command command, String... words) {
        return switch (command.parse(words)) {
            case Parsed.Failure failure -> failure.reason();
            case Parsed parsed -> "not a failure: " + parsed;
        };
    }

    private static String help(Command command, String path) throws IOException {
        var written = new StringWriter();
        Usage.help(path, command, written);
        return written.toString();
    }
}
