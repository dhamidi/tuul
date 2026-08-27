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

/// The command line: turn arguments into a message, run the application, exit
/// with what it says. Everything else lives in a library.
///
/// What tuul accepts is said once, in [#tuul()], and the help is that
/// definition read back — so a command that grows an option grows a line of
/// help, and there is no second copy of the command surface to keep in step.

void main(String[] args) throws IOException {
    var out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
    var err = new BufferedWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8));
    var status = run(List.of(args), out, err);
    out.flush();
    err.flush();
    System.exit(status);
}

int run(List<String> args, Writer out, Writer err) throws IOException {
    return switch (tuul().parse(args)) {
        case Parsed.Help help -> help(help.path(), help.command(), out);
        case Parsed.Failure failure -> ask(Message.error(failure.reason()), out, err);
        case Parsed.Values values -> dispatch(values, out, err);
    };
}

/// Everything tuul does, and the only place it is written down.
Command tuul() {
    var docs = Command.named("docs", "describe a type from the project, vendor/ or the JDK")
            .flag("json", "print the description as JSON")
            .flag("all", "include non-public members")
            .repeated("source-path", "where to look for sources (default: src)")
            .repeated("vendor", "where to look for jars (default: vendor)")
            .optional("symbol", "the type to describe");
    for (var section : Docs.SECTIONS) docs = docs.flag(section, "print only the " + section + " section");

    return Command.named("tuul", "a toolchain for modern Java")
            .command(Command.named("new", "scaffold a project")
                    .optional("name", "what to call it"))
            .command(Command.named("build", "compile native/ and src/ into build/")
                    .flag("native", "build only the native modules"))
            .command(Command.named("run", "run an entrypoint, arguments and all")
                    .optional("entrypoint", "which entrypoint (default: cli)")
                    .passthrough("arguments", "what to pass to the application"))
            .command(Command.named("test", "compile and run test/"))
            .command(docs)
            .command(Command.named("bind", "generate a Java binding for a native module")
                    .value("package", "the package to write it into (default: the module)")
                    .value("class", "the class to call it (default: Api)")
                    .optional("module", "the native module to bind"))
            .command(Command.named("self-test", "build a project in a temporary directory and exercise tuul on it"))
            .command(Command.named("message", "run one JSON message read from stdin"))
            .command(Command.named("help", "this"));
}

/// A parsed command line is already a message: the values carry the names the
/// applications read. Only `docs` needs translating, because several flags
/// there add up to one field.
int dispatch(Parsed.Values parsed, Writer out, Writer err) throws IOException {
    var values = parsed.values();
    return switch (parsed.command().name()) {
        case "new" -> manage(Message.of("project.new", values), out, err);
        case "build" -> manage(Message.of("project.build", values), out, err);
        case "run" -> manage(Message.of("project.run", values), out, err);
        case "test" -> manage(Message.of("project.test", values), out, err);
        case "bind" -> manage(Message.of("project.bind", values), out, err);
        case "self-test" -> manage(Message.of("project.selftest", values), out, err);
        case "docs" -> ask(docs(values), out, err);
        case "message" -> ask(stdin(), out, err);
        case "help" -> help("tuul", tuul(), out);
        default -> help(parsed.path(), parsed.command(), out);
    };
}

/// `tuul docs invoicing.Invoice --methods --json`. The sections are one field
/// made of several flags, and both the flags and this come from
/// [Docs#SECTIONS], so a new section needs saying in one place.
Message docs(Json.Object values) {
    var sections = new ArrayList<Json>();
    for (var section : Docs.SECTIONS) {
        if (values.flag(section)) sections.add(Json.of(section));
    }
    return Message.of("docs.query")
            .with("symbol", values.string("symbol", ""))
            .with("json", values.flag("json"))
            .with("all", values.flag("all"))
            .with("sections", Json.Array.of(sections))
            .with("sourcePath", Json.Array.of(values.list("source-path")))
            .with("vendorPath", Json.Array.of(values.list("vendor")));
}

/// `tuul message` — the same application, driven by a JSON message on stdin,
/// which is how an agent talks to it without going through flags at all.
Message stdin() {
    var value = Json.parse(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    return value instanceof Json.Object body ? new Message(body) : Message.error("a message must be a JSON object");
}

int ask(Message message, Writer out, Writer err) {
    return docs.App.of(docs.State.of(directory("src"), directory("vendor")), out, err).dispatch(message).exit();
}

int manage(Message message, Writer out, Writer err) {
    return project.App.of(project.State.of(Path.of(".")), out, err).dispatch(message).exit();
}

/// A tuul project keeps its code in `src/` and its dependencies in `vendor/`,
/// so neither has to be named on the command line.
List<Path> directory(String name) {
    var path = Path.of(name);
    return Files.isDirectory(path) ? List.of(path) : List.of();
}

int help(String path, Command command, Writer out) throws IOException {
    Usage.help(path, command, out);
    return 0;
}
