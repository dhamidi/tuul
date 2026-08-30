import application.Message;
import argparse.Command;
import argparse.Parsed;
import argparse.Usage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import json.Json;
import symbols.Docs;
import tuul.Version;

/// The command line: turn arguments into a message, run the application, exit
/// with what it says. Everything else lives in a library.
///
/// What tuul accepts is said once, in [#tuul()], and the help is that
/// definition read back — so a command that grows an option grows a line of
/// help, and there is no second copy of the command surface to keep in step.

public final class main {

public static void main(String[] args) throws IOException {
    var out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
    var err = new BufferedWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8));
    var status = run(List.of(args), out, err);
    out.flush();
    err.flush();
    System.exit(status);
}

static int run(List<String> args, Writer out, Writer err) throws IOException {
    return switch (tuul().parse(args)) {
        case Parsed.Help help -> help(help.path(), help.command(), out);
        case Parsed.Failure failure -> ask(Message.error(failure.reason()), out, err);
        case Parsed.Values values -> dispatch(values, out, err);
    };
}

/// Everything tuul does, and the only place it is written down.
static Command tuul() {
    var docs = Command.named("docs", "describe a type from the project, vendor/ or the JDK")
            .flag("json", "print the description as JSON")
            .flag("all", "include non-public members")
            .flag("members", "describe everything the symbol holds, not only the symbol")
            .flag("recursive", "as --members, and into subpackages too")
            .value("search", "search names and documentation instead of naming a symbol")
            .repeated("source-path", "where to look for sources (default: src)")
            .repeated("vendor", "where to look for jars (default: vendor)")
            .optional("symbol", "the type to describe");
    for (var section : Docs.SECTIONS) docs = docs.flag(section, "print only the " + section + " section");

    return Command.named("tuul", "a toolchain for modern Java")
            .flag("version", "print which tuul this is and stop")
            .command(Command.named("new", "scaffold a project")
                    .optional("name", "what to call it"))
            .command(Command.named("build", "compile native/ and src/ into build/")
                    .flag("native", "build only the native modules"))
            .command(Command.named("run", "run an entrypoint, arguments and all")
                    .optional("entrypoint", "which entrypoint (default: cli)")
                    .passthrough("arguments", "what to pass to the application"))
            .command(Command.named("test", "compile and run test/")
                    .flag("all", "run the complete suite, including integration tests")
                    .flag("integration", "run the complete suite, including integration tests"))
            .command(Command.named("install", "vendor tuul into this project, libraries and all")
                    .flag("source", "vendor the C to compile instead of prebuilt libraries"))
            .command(Command.named("add", "download Maven dependencies into vendor/")
                    .repeated("repository", "Maven repository base URI (default: Maven Central)")
                    .rest("dependencies", "group:artifact:version[:classifier] coordinates"))
            .command(docs)
            .command(Command.named("bind", "generate a Java binding for a native module")
                    .value("package", "the package to write it into (default: the module)")
                    .value("class", "the class to call it (default: Api)")
                    .optional("module", "the native module to bind"))
            .command(Command.named("browse", "read the symbol index in a browser")
                    .value("port", "which port to listen on (default: 8080)")
                    .repeated("source-path", "where to look for sources (default: src)")
                    .repeated("vendor", "where to look for jars (default: vendor)"))
            .command(Command.named("hyperspec", "run hyperspecs against an application that is already running")
                    .value("eval", 'e', "a spec to run, instead of reading files")
                    .flag("quiet", "print only what failed")
                    .optional("host", "where it is listening: :8099, localhost:8099, http://host:8099")
                    .rest("specs", "the spec files to run, or - for standard input"))
            .command(Command.named("self-test", "build a project in a temporary directory and exercise tuul on it"))
            .command(Command.named("message", "run one JSON message read from stdin"))
            .command(Command.named("help", "this"));
}

/// A parsed command line is already a message: the values carry the names the
/// applications read. Only `docs` needs translating, because several flags
/// there add up to one field.
static int dispatch(Parsed.Values parsed, Writer out, Writer err) throws IOException {
    var values = parsed.values();
    return switch (parsed.command().name()) {
        case "new" -> manage(Message.of("project.new", values), out, err);
        case "build" -> manage(Message.of("project.build", values), out, err);
        case "run" -> manage(Message.of("project.run", values), out, err);
        case "test" -> manage(Message.of("project.test", values), out, err);
        case "bind" -> manage(Message.of("project.bind", values), out, err);
        case "self-test" -> manage(Message.of("project.selftest", values), out, err);
        case "hyperspec" -> manage(Message.of("project.hyperspec", values), out, err);
        case "install" -> manage(Message.of("project.install", values), out, err);
        case "add" -> manage(Message.of("project.add", values), out, err);
        case "browse" -> browse(values, out, err);
        case "docs" -> ask(docs(values), out, err);
        case "message" -> deliver(stdin(), out, err);
        case "help" -> help("tuul", tuul(), out);
        case "tuul" -> values.flag("version") ? announce(out) : help(parsed.path(), parsed.command(), out);
        default -> help(parsed.path(), parsed.command(), out);
    };
}

/// A message read from stdin goes to whichever application handles its type,
/// so `tuul message` reaches all of tuul rather than the half of it `docs`
/// happens to be.
static int deliver(Message message, Writer out, Writer err) {
    return message.type().startsWith("project.") ? manage(message, out, err) : ask(message, out, err);
}

/// `tuul browse` is the one command that does not dispatch a message: it starts
/// a server and stays there, so there is no state for an application to end up
/// in and nothing to report when it does.
static int browse(Json.Object values, Writer out, Writer err) {
    try {
        browser.Browser.serve(paths(values, "source-path", "src"), paths(values, "vendor", "vendor"),
                Integer.parseInt(values.string("port", "8080")), out);
        return 0;
    } catch (NumberFormatException notAPort) {
        return complain("--port must be a number: " + values.string("port", ""), err);
    } catch (Exception failed) {
        return complain(failed.getMessage() == null ? failed.toString() : failed.getMessage(), err);
    }
}

/// The paths a command was given, or the conventional one when it was given
/// none and that directory is there.
static List<Path> paths(Json.Object values, String option, String fallback) {
    var given = new ArrayList<Path>();
    for (var value : values.list(option)) {
        if (value instanceof Json.Str(var directory)) given.add(Path.of(directory));
    }
    return given.isEmpty() ? directory(fallback) : List.copyOf(given);
}

static int complain(String reason, Writer err) {
    try {
        err.write("error: " + reason + "\n");
        err.flush();
    } catch (IOException unwritable) {
        // there is nowhere left to say so
    }
    return 1;
}

/// `tuul docs invoicing.Invoice --methods --json`. The sections are one field
/// made of several flags, and both the flags and this come from
/// [Docs#SECTIONS], so a new section needs saying in one place.
static Message docs(Json.Object values) {
    var sections = new ArrayList<Json>();
    for (var section : Docs.SECTIONS) {
        if (values.flag(section)) sections.add(Json.of(section));
    }
    return Message.of("docs.query")
            .with("symbol", values.string("symbol", ""))
            .with("search", values.string("search", ""))
            .with("json", values.flag("json"))
            .with("all", values.flag("all"))
            .with("members", values.flag("members"))
            .with("recursive", values.flag("recursive"))
            .with("sections", Json.Array.of(sections))
            .with("sourcePath", Json.Array.of(values.list("source-path")))
            .with("vendorPath", Json.Array.of(values.list("vendor")));
}

/// `tuul message` — the same application, driven by a JSON message on stdin,
/// which is how an agent talks to it without going through flags at all.
///
/// A message is `{"type": ..., "body": {...}}`. The payload goes inside `body`
/// rather than beside the type, so that a payload may have a field called
/// `type` — see [application.Envelope]. `{"type": "project.natives"}` is a
/// message with an empty payload and needs no `body`.
///
/// A field that is neither the envelope's nor inside `body` is refused rather
/// than read as one or the other. Both guesses are wrong in a way nothing would
/// report: treated as envelope it is ignored, and treated as payload it brings
/// back the ambiguity `body` exists to end. Saying so costs one error and
/// teaches the shape.
static Message stdin() {
    var value = Json.parse(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    if (!(value instanceof Json.Object document)) return Message.error("a message must be a JSON object");
    var stray = document.fields().keySet().stream().filter(name -> !ENVELOPE.contains(name)).sorted().toList();
    if (!stray.isEmpty()) {
        return Message.error("a message is {\"type\": ..., \"body\": {...}} — "
                + "move " + String.join(", ", stray) + " inside body");
    }
    return Message.from(document);
}

/// The only fields a message has outside its payload.
static final java.util.Set<String> ENVELOPE = java.util.Set.of(Message.TYPE, Message.AT, Message.BODY);

static int ask(Message message, Writer out, Writer err) {
    return docs.App.of(docs.State.of(directory("src"), directory("vendor")), out, err).dispatch(message).exit();
}

static int manage(Message message, Writer out, Writer err) {
    var console = System.console();
    return project.App.of(project.State.of(Path.of(".")), out, err,
            console != null && console.isTerminal())
            .dispatch(message).exit();
}

/// A tuul project keeps its code in `src/` and its dependencies in `vendor/`,
/// so neither has to be named on the command line.
static List<Path> directory(String name) {
    var path = Path.of(name);
    return Files.isDirectory(path) ? List.of(path) : List.of();
}

static int announce(Writer out) throws IOException {
    out.write(Version.describe() + "\n");
    return 0;
}

static int help(String path, Command command, Writer out) throws IOException {
    Usage.help(path, command, out);
    return 0;
}

}
