import application.Message;
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

void main(String[] args) throws IOException {
    var out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
    var err = new BufferedWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8));
    var status = run(List.of(args), out, err);
    out.flush();
    err.flush();
    System.exit(status);
}

int run(List<String> args, Writer out, Writer err) throws IOException {
    if (args.isEmpty()) return usage(out);
    var rest = args.subList(1, args.size());
    return switch (args.getFirst()) {
        case "docs" -> ask(docs(rest), out, err);
        case "message" -> ask(stdin(), out, err);
        case "new" -> manage(Message.of("project.new").with("name", rest.isEmpty() ? "" : rest.getFirst()), out, err);
        case "build" -> manage(Message.of("project.build"), out, err);
        case "run" -> manage(started(rest), out, err);
        case "test" -> manage(Message.of("project.test"), out, err);
        case "self-test" -> manage(Message.of("project.selftest"), out, err);
        case "help", "-h", "--help" -> usage(out);
        default -> ask(Message.error("unknown command: " + args.getFirst()), out, err);
    };
}

/// `tuul run [entrypoint] -- <arguments>` — everything after `--` belongs to
/// the application, not to tuul.
Message started(List<String> args) {
    var separator = args.indexOf("--");
    var head = separator < 0 ? args : args.subList(0, separator);
    var tail = separator < 0 ? List.<String>of() : args.subList(separator + 1, args.size());
    return Message.of("project.run")
            .with("entrypoint", head.isEmpty() ? "" : head.getFirst())
            .with("arguments", Json.Array.strings(tail));
}

/// `tuul docs invoicing.Invoice --methods --json`
Message docs(List<String> args) {
    var message = Message.of("docs.query");
    var sections = new ArrayList<Json>();
    var sourcePath = new ArrayList<Json>();
    var vendorPath = new ArrayList<Json>();
    for (var i = 0; i < args.size(); i++) {
        var arg = args.get(i);
        var section = arg.startsWith("--") ? arg.substring(2) : "";
        if (arg.equals("--source-path") && i + 1 < args.size()) sourcePath.add(Json.of(args.get(++i)));
        else if (arg.equals("--vendor") && i + 1 < args.size()) vendorPath.add(Json.of(args.get(++i)));
        else if (arg.equals("--json")) message = message.with("json", true);
        else if (arg.equals("--all")) message = message.with("all", true);
        else if (Docs.SECTIONS.contains(section)) sections.add(Json.of(section));
        else if (arg.startsWith("-")) return Message.error("unknown option: " + arg);
        else message = message.with("symbol", arg);
    }
    return message
            .with("sections", Json.Array.of(sections))
            .with("sourcePath", Json.Array.of(sourcePath))
            .with("vendorPath", Json.Array.of(vendorPath));
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

int usage(Writer out) throws IOException {
    out.write("""
            tuul — a toolchain for modern Java

            usage:
              tuul new <name>                scaffold a project
              tuul build                     compile src/ into build/
              tuul run [entry] -- <args>     run an entrypoint, arguments and all
              tuul test                      compile and run test/
              tuul docs <symbol> [options]   describe a type from the project, vendor/ or the JDK
              tuul self-test                 build a project in a temporary directory and exercise tuul on it
              tuul message                   run one JSON message read from stdin
              tuul help                      this

            docs options:
              --json                         print the description as JSON
              --doc                          print the whole doc comment
              --extends --implements         print only that section
              --methods --fields             print only that section
              --all                          include non-public members
              --source-path <dir>            where to look for sources (default: src)
              --vendor <dir>                 where to look for jars (default: vendor)
            """);
    return 0;
}
