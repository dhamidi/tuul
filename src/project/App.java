package project;

import application.Application;
import application.Effect;
import application.Message;
import application.Step;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import json.Json;
import selftest.SelfTest;

/// The project commands as one application: `new`, `build`, `run`, `test` and
/// `self-test`.
///
/// They share a pipeline rather than repeating one. `run` and `test` both start
/// by compiling, and what happens when the compile succeeds is decided by what
/// the application was asked for — the build is one message, and the answer to
/// it is another.
public final class App {

    private static final int DETAIL = 12;

    private App() {}

    public static Application<State> of(State initial, Writer out, Writer err) {
        return Application.of(initial)
                .on("project.new", App::create)
                .on("project.build", App::build)
                .on("project.run", App::run)
                .on("project.test", App::test)
                .on("project.selftest", App::selftest)
                .on("project.created", App::created)
                .on("project.native.built", App::compiled)
                .on("project.built", App::built)
                .on("project.exited", App::exited)
                .on("selftest.done", App::finished)
                .on("error", App::failed)
                .effect("project.scaffold", App::scaffold)
                .effect("project.native", (effect, emit) -> buildNative(effect, emit, out))
                .effect("project.compile", App::compile)
                .effect("project.launch", (effect, emit) -> launch(effect, emit, out))
                .effect("project.launch.tests", (effect, emit) -> launchTests(effect, emit, out))
                .effect("project.selftest", App::selfTest)
                .effect("project.report", (effect, _) -> write(effect, out))
                .effect("project.problem", (effect, _) -> write(effect, err));
    }

    private static Step<State> create(State state, Message message) {
        var name = message.string("name", "");
        if (name.isEmpty()) return Step.of(state.failed(), problem("tuul new needs a name"));
        return Step.of(state, Effect.of("project.scaffold")
                .with("directory", state.directory().toString())
                .with("name", name));
    }

    private static Step<State> created(State state, Message message) {
        var directory = message.string("directory", "");
        return Step.of(state, report("created " + directory
                + "\n  cd " + directory + " && tuul run"));
    }

    /// Every build starts with the native modules: they are what the Java is
    /// compiled and run against, so nothing else can go first.
    private static Step<State> build(State state, Message message) {
        var action = message.flag("native") ? State.Action.NATIVE : State.Action.BUILD;
        return Step.of(state.doing(action), native_(state));
    }

    private static Step<State> run(State state, Message message) {
        var arguments = new ArrayList<String>();
        for (var argument : message.list("arguments")) {
            if (argument instanceof Json.Str(var text)) arguments.add(text);
        }
        return Step.of(state.running(message.string("entrypoint", ""), arguments), native_(state));
    }

    private static Step<State> test(State state, Message message) {
        return Step.of(state.doing(State.Action.TEST), native_(state));
    }

    private static Step<State> compiled(State state, Message message) {
        if (state.action() != State.Action.NATIVE) return Step.of(state, compile(state, false));
        var built = message.list("built").size();
        var current = message.list("current").size();
        return Step.of(state, report(built == 0 && current == 0
                ? "no native modules"
                : "built " + built + " native module(s), " + current + " already current"));
    }

    /// One compile, three meanings: report it, run the application, or go on to
    /// compile the tests and run those.
    private static Step<State> built(State state, Message message) {
        if (message.flag("tests")) return Step.of(state, Effect.of("project.launch.tests").with("directory", where(state)));
        return switch (state.action()) {
            case BUILD -> Step.of(state, report("compiled " + count(message) + " classes"));
            case RUN -> Step.of(state, Effect.of("project.launch")
                    .with("directory", where(state))
                    .with("entrypoint", state.entrypoint())
                    .with("arguments", Json.Array.strings(state.arguments())));
            case TEST -> Step.of(state, compile(state, true));
            case NATIVE, NONE -> Step.of(state);
        };
    }

    private static Step<State> exited(State state, Message message) {
        return Step.of(state.exited((int) number(message, "status")));
    }

    private static Step<State> selftest(State state, Message message) {
        return Step.of(state, Effect.of("project.selftest"));
    }

    /// The self-test speaks for itself: one line per check, and the directory
    /// it left behind when something failed.
    private static Step<State> finished(State state, Message message) {
        var text = new StringBuilder();
        for (var check : message.list("checks")) {
            if (!(check instanceof Json.Object entry)) continue;
            var ok = entry.flag("ok");
            text.append(ok ? "ok   " : "FAIL ").append(entry.string("what", "")).append('\n');
            if (!ok) text.append(detail(entry.string("detail", "")));
        }
        var failed = (long) number(message, "failed");
        var passed = (long) number(message, "passed");
        text.append(passed).append('/').append(passed + failed).append(" checks passed\n");
        text.append(message.flag("kept")
                ? "kept " + message.string("directory", "") + " for inspection"
                : "removed " + message.string("directory", ""));
        return failed == 0
                ? Step.of(state, report(text.toString()))
                : Step.of(state.failed(), problem(text.toString()));
    }

    private static Step<State> failed(State state, Message message) {
        return Step.of(state.failed(), problem("error: " + message.string("reason", "unknown")));
    }

    private static void scaffold(Effect effect, Effect.Emitter emit) throws IOException {
        var directory = Path.of(effect.string("directory", ".")).resolve(effect.string("name", ""));
        var library = Scaffold.create(directory, effect.string("name", ""));
        emit.emit(Message.of("project.created")
                .with("directory", directory.toString())
                .with("library", library));
    }

    private static void buildNative(Effect effect, Effect.Emitter emit, Writer out) throws Exception {
        var layout = new Layout(Path.of(effect.string("directory", ".")));
        var result = Native.build(layout, out);
        if (!result.ok()) {
            emit.emit(Message.error(String.join("\n", result.problems())));
            return;
        }
        emit.emit(Message.of("project.native.built")
                .with("built", Json.Array.strings(result.built()))
                .with("current", Json.Array.strings(result.current())));
    }

    private static void compile(Effect effect, Effect.Emitter emit) throws IOException {
        var layout = new Layout(Path.of(effect.string("directory", ".")));
        var tests = effect.flag("tests");
        var result = tests ? Build.compileTests(layout) : Build.compile(layout);
        if (!result.ok()) {
            emit.emit(Message.error(String.join("\n", result.problems())));
            return;
        }
        emit.emit(Message.of("project.built")
                .with("classes", Json.of(result.classes()))
                .with("tests", tests));
    }

    private static void launch(Effect effect, Effect.Emitter emit, Writer out) throws Exception {
        var layout = new Layout(Path.of(effect.string("directory", ".")));
        var entrypoint = layout.entrypoint(effect.string("entrypoint", ""));
        if (entrypoint.isEmpty()) throw new IOException("no entrypoint to run — add src/<name>/main.java");
        var arguments = new ArrayList<String>();
        for (var argument : effect.list("arguments")) {
            if (argument instanceof Json.Str(var text)) arguments.add(text);
        }
        start(Launch.java(List.of(), List.of(layout.classes(), layout.entry(entrypoint)), "main", arguments),
                layout, emit, out);
    }

    private static void launchTests(Effect effect, Effect.Emitter emit, Writer out) throws Exception {
        var layout = new Layout(Path.of(effect.string("directory", ".")));
        start(Launch.java(List.of(), List.of(layout.classes(), layout.tests()), "run", List.of()), layout, emit, out);
    }

    private static void start(List<String> command, Layout layout, Effect.Emitter emit, Writer out) throws Exception {
        var status = Launch.run(command, layout.root(), out);
        emit.emit(Message.of("project.exited").with("status", Json.of(status)));
    }

    private static void selfTest(Effect effect, Effect.Emitter emit) throws Exception {
        var report = SelfTest.run();
        emit.emit(Message.of("selftest.done")
                .with("directory", report.directory().toString())
                .with("kept", report.kept())
                .with("passed", Json.of(report.passed()))
                .with("failed", Json.of(report.failed()))
                .with("checks", Json.Array.of(report.checks().stream()
                        .map(check -> (Json) Json.Object.of()
                                .with("what", check.what())
                                .with("ok", check.ok())
                                .with("detail", check.detail()))
                        .toList())));
    }

    private static void write(Effect effect, Writer out) throws IOException {
        var text = effect.string("text", "");
        out.write(text.endsWith("\n") ? text : text + "\n");
        out.flush();
    }

    private static Effect native_(State state) {
        return Effect.of("project.native").with("directory", where(state));
    }

    private static Effect compile(State state, boolean tests) {
        return Effect.of("project.compile").with("directory", where(state)).with("tests", tests);
    }

    private static Effect report(String text) {
        return Effect.of("project.report").with("text", text);
    }

    private static Effect problem(String text) {
        return Effect.of("project.problem").with("text", text);
    }

    private static String where(State state) {
        return state.directory().toString();
    }

    private static double number(Message message, String name) {
        return message.get(name) instanceof Json.Num(var value) ? value : 0;
    }

    private static long count(Message message) {
        return (long) number(message, "classes");
    }

    /// Enough of what went wrong to act on, not the whole log.
    private static String detail(String text) {
        if (text.isEmpty()) return "";
        var lines = text.lines().limit(DETAIL).map(line -> "       " + line + "\n").toList();
        var more = text.lines().count() > DETAIL ? "       ...\n" : "";
        return String.join("", lines) + more;
    }
}
