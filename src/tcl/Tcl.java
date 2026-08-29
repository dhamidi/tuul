package tcl;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tcl.Script.Part;
import tcl.Script.Word;

/// An embedded Tcl interpreter whose values are JVM objects.
///
/// Source evaluation reads one command at a time from a [Reader]. The caller
/// owns the reader. One thread can use an interpreter at a time. The same
/// thread can make a nested call.
public final class Tcl {

    private static final ThreadLocal<ArrayDeque<Tcl>> CURRENT = ThreadLocal.withInitial(ArrayDeque::new);

    private final Namespace root = new Namespace("", null);
    private final ArrayList<Frame> frames = new ArrayList<>();
    private final ArrayList<Evaluation> evaluations = new ArrayList<>();
    private final Object gate = new Object();
    private Thread owner;
    private int entries;
    private long commandCount;
    private List<Object> lastErrorStack = List.of();

    private Tcl() {
        frames.add(new Frame(root, root.variables, false, "", List.of()));
        install();
    }

    /// Creates an interpreter with builtins in the root namespace.
    public static Tcl of() {
        return new Tcl();
    }

    /// Returns the interpreter of the current nested evaluation.
    public static Tcl current() {
        var stack = CURRENT.get();
        if (stack.isEmpty()) throw error("no Tcl interpreter is running", "TCL", "CURRENT");
        return stack.getLast();
    }

    /// Stores a root-relative variable and creates missing namespaces.
    public Tcl set(String name, Object value) {
        locked(() -> {
            var ref = javaVariable(name, true);
            set(ref, value);
            return null;
        });
        return this;
    }

    /// Returns a root-relative variable.
    public Object get(String name) {
        return locked(() -> get(javaVariable(name, false), name));
    }

    /// Returns whether a root-relative variable is set.
    public boolean exists(String name) {
        return locked(() -> {
            var ref = javaVariableOrNull(name);
            return ref != null && exists(ref);
        });
    }

    /// Registers a root-relative command and creates missing namespaces.
    public Tcl command(String name, Command command) {
        locked(() -> {
            register(name, command, "native", true);
            return null;
        });
        return this;
    }

    /// Registers a host object as a root-relative command.
    public Tcl command(String name, Object target) {
        return command(name, (tcl, args) -> Methods.call(tcl, target, args));
    }

    /// Evaluates source text with no origin.
    public Object eval(String script) {
        return eval(script, "");
    }

    /// Evaluates source text with the specified origin.
    public Object eval(String script, String origin) {
        try {
            return eval(new StringReader(script), 1, origin);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Evaluates source from the caller-owned reader.
    public Object eval(Reader reader) throws IOException {
        return eval(reader, 1, "");
    }

    /// Evaluates source whose first character is on `firstLine`.
    public Object eval(Reader reader, int firstLine) throws IOException {
        return eval(reader, firstLine, "");
    }

    /// Evaluates source one command at a time and does not close the reader.
    public Object eval(Reader reader, int firstLine, String origin) throws IOException {
        enter();
        try {
            var source = new Parser.Source(reader, firstLine);
            Object result = "";
            while (true) {
                var command = Parser.next(source, origin, false);
                if (command == null) return result;
                result = execute(command, origin, "source");
            }
        } catch (TclException.Return completion) {
            return finishAtBoundary(completion);
        } catch (TclException.Break completion) {
            throw error("invoked break outside of a loop", "TCL", "RESULT", "BREAK");
        } catch (TclException.Continue completion) {
            throw error("invoked continue outside of a loop", "TCL", "RESULT", "CONTINUE");
        } finally {
            leave();
        }
    }

    /// Evaluates an already parsed script.
    public Object eval(Script script) {
        return locked(() -> {
            try {
                return run(script, script.origin().isEmpty() ? "eval" : "source");
            } catch (TclException.Return completion) {
                return finishAtBoundary(completion);
            } catch (TclException.Break completion) {
                throw error("invoked break outside of a loop", "TCL", "RESULT", "BREAK");
            } catch (TclException.Continue completion) {
                throw error("invoked continue outside of a loop", "TCL", "RESULT", "CONTINUE");
            }
        });
    }

    /// Evaluates command lists without parsing or substitution.
    public Object eval(List<? extends List<?>> commands) {
        return eval(Script.lists(commands));
    }

    /// Dispatches one command list whose words are already values.
    public Object invoke(Object... argv) {
        return invoke(Arrays.asList(argv));
    }

    /// Dispatches one command list whose words are already values.
    public Object invoke(List<?> argv) {
        return locked(() -> {
            try {
                return dispatch(new ArrayList<>(argv));
            } catch (TclException.Return completion) {
                return finishAtBoundary(completion);
            } catch (TclException.Break completion) {
                throw error("invoked break outside of a loop", "TCL", "RESULT", "BREAK");
            } catch (TclException.Continue completion) {
                throw error("invoked continue outside of a loop", "TCL", "RESULT", "CONTINUE");
            }
        });
    }

    /// Calls a root-relative command with JVM arguments.
    public Object call(String name, Object... args) {
        var words = new ArrayList<Object>(args.length + 1);
        words.add(name.contains("::") ? name : "::" + name);
        for (var arg : args) words.add(arg);
        return invoke(words);
    }

    /// Returns a predicate that converts the command result to a boolean.
    public Predicate<Object> predicate(String command) {
        return value -> Values.bool(call(command, value));
    }

    /// Returns a function that calls the root-relative command with one value.
    public Function<Object, Object> function(String command) {
        return value -> call(command, value);
    }

    /// Returns a consumer that calls the root-relative command with one value.
    public Consumer<Object> consumer(String command) {
        return value -> call(command, value);
    }

    /// Returns a runnable that calls the root-relative command with `args`.
    public Runnable runnable(String command, Object... args) {
        return () -> call(command, args);
    }

    /// Returns a callable that calls the root-relative command with `args`.
    public Callable<Object> callable(String command, Object... args) {
        return () -> call(command, args);
    }

    /// Returns a comparator that converts the command result to an integer.
    public Comparator<Object> comparator(String command) {
        return (left, right) -> Math.toIntExact(Values.integer(call(command, left, right)));
    }

    private void install() {
        builtin("set", this::setCommand);
        builtin("unset", this::unsetCommand);
        builtin("incr", this::incrCommand);
        builtin("expr", this::exprCommand);
        builtin("if", this::ifCommand);
        builtin("while", this::whileCommand);
        builtin("for", this::forCommand);
        builtin("foreach", this::foreachCommand);
        builtin("switch", this::switchCommand);
        builtin("proc", this::procCommand);
        builtin("return", this::returnCommand);
        builtin("break", (tcl, args) -> {
            arity("break", args, 0, 0);
            throw new TclException.Break();
        });
        builtin("continue", (tcl, args) -> {
            arity("continue", args, 0, 0);
            throw new TclException.Continue();
        });
        builtin("error", this::errorCommand);
        builtin("throw", this::throwCommand);
        builtin("catch", this::catchCommand);
        builtin("try", this::tryCommand);
        builtin("uplevel", this::uplevelCommand);
        builtin("upvar", this::upvarCommand);
        builtin("global", this::globalCommand);
        builtin("variable", this::variableCommand);

        var namespace = namespace(root, "namespace", true);
        namespaceCommand(namespace, "eval", this::namespaceEval);
        namespaceCommand(namespace, "current", this::namespaceCurrent);
        namespaceCommand(namespace, "parent", this::namespaceParent);
        namespaceCommand(namespace, "children", this::namespaceChildren);
        namespaceCommand(namespace, "exists", this::namespaceExists);
        namespaceCommand(namespace, "delete", this::namespaceDelete);
        namespaceCommand(namespace, "tail", this::namespaceTail);
        namespaceCommand(namespace, "qualifiers", this::namespaceQualifiers);
        namespaceCommand(namespace, "which", this::namespaceWhich);
        namespaceCommand(namespace, "origin", this::namespaceOrigin);
        namespaceCommand(namespace, "export", this::namespaceExport);
        namespaceCommand(namespace, "import", this::namespaceImport);
        namespaceCommand(namespace, "forget", this::namespaceForget);
        namespaceCommand(namespace, "upvar", this::namespaceUpvar);
        namespaceCommand(namespace, "path", this::namespacePath);
        namespaceCommand(namespace, "unknown", this::namespaceUnknown);
        namespaceCommand(namespace, "code", this::namespaceCode);
        namespaceCommand(namespace, "inscope", this::namespaceInscope);
        var ensembleNs = namespace(namespace, "ensemble", true);
        namespaceCommand(ensembleNs, "create", this::ensembleCreate);
        namespaceCommand(ensembleNs, "configure", this::ensembleConfigure);
        namespaceCommand(ensembleNs, "exists", this::ensembleExists);
        root.commands.put("namespace", ref(new Ensemble(namespace, "::namespace"), root, "::namespace", "ensemble"));
        namespace.commands.put("ensemble", ref(new Ensemble(ensembleNs, "::namespace::ensemble"), namespace,
                "::namespace::ensemble", "ensemble"));

        var info = namespace(root, "info", true);
        namespaceCommand(info, "args", this::infoArgs);
        namespaceCommand(info, "body", this::infoBody);
        namespaceCommand(info, "cmdcount", this::infoCmdcount);
        namespaceCommand(info, "cmdtype", this::infoCmdtype);
        namespaceCommand(info, "commands", this::infoCommands);
        namespaceCommand(info, "complete", this::infoComplete);
        namespaceCommand(info, "default", this::infoDefault);
        namespaceCommand(info, "errorstack", this::infoErrorstack);
        namespaceCommand(info, "exists", this::infoExists);
        namespaceCommand(info, "frame", this::infoFrame);
        namespaceCommand(info, "functions", this::infoFunctions);
        namespaceCommand(info, "globals", this::infoGlobals);
        namespaceCommand(info, "level", this::infoLevel);
        namespaceCommand(info, "locals", this::infoLocals);
        namespaceCommand(info, "procs", this::infoProcs);
        namespaceCommand(info, "script", this::infoScript);
        namespaceCommand(info, "vars", this::infoVars);
        root.commands.put("info", ref(new Ensemble(info, "::info"), root, "::info", "ensemble"));
    }

    private Object run(Script script, String type) {
        Object result = "";
        for (var command : script.commands()) result = execute(command, script.origin(), type);
        return result;
    }

    private Object execute(Script.Command command, String origin, String type) {
        if (Thread.interrupted()) throw error("evaluation interrupted", "TCL", "INTERRUPTED");
        evaluations.add(new Evaluation(command, origin, type));
        List<Object> argv = null;
        try {
            argv = substitute(command.words());
            commandCount++;
            return dispatch(argv);
        } catch (TclException.Error failure) {
            var traced = trace(failure, command, origin, argv);
            remember(traced);
            throw traced;
        } finally {
            evaluations.removeLast();
        }
    }

    private ArrayList<Object> substitute(List<Word> words) {
        var argv = new ArrayList<Object>();
        for (var word : words) {
            if (word.parts().size() == 1 && word.parts().getFirst() instanceof Part.Expanded(var expanded)) {
                argv.addAll(Values.list(value(expanded)));
                continue;
            }
            argv.add(value(word));
        }
        return argv;
    }

    private Object value(Word word) {
        if (word.braced()) return word.body();
        if (word.parts().isEmpty()) return "";
        if (word.parts().size() == 1) return value(word.parts().getFirst());
        var result = new StringBuilder();
        for (var part : word.parts()) result.append(Values.string(value(part)));
        return result.toString();
    }

    private Object value(Part part) {
        return switch (part) {
            case Part.Text(var text) -> text;
            case Part.Value(var object) -> object;
            case Part.Substitution(var script) -> run(script, script.origin().isEmpty() ? "eval" : "source");
            case Part.Expanded(var word) -> value(word);
            case Part.Variable(var name, var index) -> {
                if (name.equals("$")) yield "$";
                var actual = index == null ? name : name + "(" + Values.string(value(index)) + ")";
                yield get(variable(actual, false), actual);
            }
        };
    }

    private Object dispatch(List<Object> argv) {
        if (argv.isEmpty()) return "";
        var first = argv.getFirst();
        var args = new ArrayList<>(argv.subList(1, argv.size()));
        if (first instanceof String name) {
            var found = commandRef(name, frame().namespace);
            if (found != null) return found.command.call(this, args);
            var unknown = frame().namespace.unknown;
            if (unknown != null && !Values.string(unknown).isEmpty()) {
                var prefix = new ArrayList<>(Values.list(unknown));
                prefix.addAll(argv);
                return dispatch(prefix);
            }
            throw error("invalid command name \"" + name + "\"", "TCL", "LOOKUP", "COMMAND", name);
        }
        return Methods.call(this, first, args);
    }

    private Object setCommand(Tcl ignored, List<Object> args) {
        arity("set", args, 1, 2);
        var name = Values.string(args.getFirst());
        var ref = variable(name, args.size() == 2);
        if (args.size() == 1) return get(ref, name);
        set(ref, args.get(1));
        return args.get(1);
    }

    private Object unsetCommand(Tcl ignored, List<Object> args) {
        var noComplain = !args.isEmpty() && Values.string(args.getFirst()).equals("-nocomplain");
        var start = noComplain ? 1 : 0;
        if (start == args.size()) return "";
        for (var at = start; at < args.size(); at++) {
            var name = Values.string(args.get(at));
            var ref = variableOrNull(name);
            if (ref == null || !exists(ref)) {
                if (!noComplain) throw missingVariable(name);
                continue;
            }
            unset(ref);
        }
        return "";
    }

    private Object incrCommand(Tcl ignored, List<Object> args) {
        arity("incr", args, 1, 2);
        var name = Values.string(args.getFirst());
        var ref = variable(name, false);
        final long value;
        try {
            value = Math.addExact(Values.integer(get(ref, name)), args.size() == 1 ? 1 : Values.integer(args.get(1)));
        } catch (ArithmeticException e) {
            throw error("integer value too large", "ARITH", "OVERFLOW", "integer value too large");
        }
        set(ref, value);
        return value;
    }

    private Object exprCommand(Tcl ignored, List<Object> args) {
        if (args.isEmpty()) wrongArgs("expr arg ?arg ...?");
        if (args.size() == 1 && !(args.getFirst() instanceof String)) {
            var value = args.getFirst();
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return ((Number) value).longValue();
            }
            if (value instanceof Number || value instanceof Boolean) return value;
        }
        return new Expr(this, args.stream().map(Values::string).reduce((a, b) -> a + " " + b).orElse("")).parse();
    }

    Object expressionVariable(String name) {
        var arrayIndex = index(name);
        var actual = arrayIndex == null ? name : base(name) + "(" + Values.string(interpolate(arrayIndex)) + ")";
        return get(variable(actual, false), actual);
    }

    Object expressionScript(String source) {
        return run(Script.parse(source), "eval");
    }

    Object expressionQuoted(String source) {
        return interpolate(source);
    }

    private Object interpolate(String source) {
        var script = Script.parse("::set __expression__ \"" + source + "\"");
        return value(script.commands().getFirst().words().get(2));
    }

    Object callback(Object command, Object[] arguments) {
        return locked(() -> {
            var args = Arrays.asList(arguments);
            if (command instanceof Command callable) return callable.call(this, new ArrayList<>(args));
            return call(Values.string(command), arguments);
        });
    }

    private Object ifCommand(Tcl ignored, List<Object> args) {
        var at = 0;
        while (at < args.size()) {
            var keyword = Values.string(args.get(at));
            if (keyword.equals("else")) {
                if (at + 2 != args.size()) wrongArgs("if expr ?then? body ?elseif expr ?then? body ...? ?else body?");
                return runBody(args.get(at + 1), at + 1);
            }
            if (keyword.equals("elseif")) at++;
            if (at + 1 >= args.size()) wrongArgs("if expr ?then? body ?elseif expr ?then? body ...? ?else body?");
            var test = args.get(at++);
            if (at < args.size() && Values.string(args.get(at)).equals("then")) at++;
            if (at >= args.size()) wrongArgs("if expr ?then? body ?elseif expr ?then? body ...? ?else body?");
            var body = args.get(at++);
            if (test(test)) return runBody(body, at - 1);
            if (at < args.size() && !Values.string(args.get(at)).equals("elseif")
                    && !Values.string(args.get(at)).equals("else")) wrongArgs("if expr ?then? body ?elseif expr ?then? body ...? ?else body?");
        }
        return "";
    }

    private Object whileCommand(Tcl ignored, List<Object> args) {
        arity("while", args, 2, 2);
        while (test(args.getFirst())) {
            try {
                runBody(args.get(1), 1);
            } catch (TclException.Continue completion) {
                continue;
            } catch (TclException.Break completion) {
                break;
            }
        }
        return "";
    }

    private Object forCommand(Tcl ignored, List<Object> args) {
        arity("for", args, 4, 4);
        runBody(args.get(0), 0);
        while (test(args.get(1))) {
            try {
                runBody(args.get(3), 3);
            } catch (TclException.Continue completion) {
                // The next script still runs.
            } catch (TclException.Break completion) {
                break;
            }
            runBody(args.get(2), 2);
        }
        return "";
    }

    private Object foreachCommand(Tcl ignored, List<Object> args) {
        arity("foreach", args, 3, 3);
        var names = Values.list(args.getFirst()).stream().map(Values::string).toList();
        if (names.isEmpty()) wrongArgs("foreach varList collection body");
        var groups = collection(args.get(1), names.size());
        try {
            while (groups.hasNext()) {
                var group = groups.next();
                for (var at = 0; at < names.size(); at++) set(variable(names.get(at), true), at < group.size() ? group.get(at) : "");
                try {
                    runBody(args.get(2), 2);
                } catch (TclException.Continue completion) {
                    continue;
                } catch (TclException.Break completion) {
                    break;
                }
            }
        } finally {
            if (groups instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    throw error("cannot close iteration source: " + e.getMessage(), "JAVA", e.getClass().getName());
                }
            }
        }
        return "";
    }

    private Object switchCommand(Tcl ignored, List<Object> args) {
        var mode = "exact";
        var nocase = false;
        var at = 0;
        while (at < args.size()) {
            var option = Values.string(args.get(at));
            if (option.equals("--")) {
                at++;
                break;
            }
            if (!option.startsWith("-")) break;
            switch (option) {
                case "-exact" -> mode = "exact";
                case "-glob" -> mode = "glob";
                case "-regexp" -> mode = "regexp";
                case "-nocase" -> nocase = true;
                default -> throw error("bad option \"" + option + "\"");
            }
            at++;
        }
        if (at >= args.size()) wrongArgs("switch ?options? string pattern body ?pattern body ...?");
        var value = Values.string(args.get(at++));
        var pairs = new ArrayList<Object>();
        if (args.size() - at == 1) pairs.addAll(Values.list(args.get(at)));
        else pairs.addAll(args.subList(at, args.size()));
        if ((pairs.size() & 1) != 0) throw error("extra switch pattern with no body");
        var selected = -1;
        var fallback = -1;
        for (var index = 0; index < pairs.size(); index += 2) {
            var pattern = Values.string(pairs.get(index));
            if (pattern.equals("default")) fallback = index;
            else if (matches(mode, nocase, pattern, value)) {
                selected = index;
                break;
            }
        }
        if (selected < 0) selected = fallback;
        if (selected < 0) return "";
        while (selected < pairs.size() && Values.string(pairs.get(selected + 1)).equals("-")) selected += 2;
        return selected < pairs.size() ? runBody(pairs.get(selected + 1)) : "";
    }

    private Object procCommand(Tcl ignored, List<Object> args) {
        arity("proc", args, 3, 3);
        var name = Values.string(args.get(0));
        var parameters = parseParameters(args.get(1));
        var bodySource = args.get(2);
        var body = scriptArgument(bodySource, 2);
        var bodyFirstLine = argumentLine(2);
        var target = commandTarget(name, true);
        var qualified = fullName(target.namespace, target.tail);
        var proc = new Proc(name, target.namespace, parameters, bodySource, body, bodyFirstLine);
        target.namespace.commands.put(target.tail, ref(proc, target.namespace, qualified, "proc"));
        return "";
    }

    private Object returnCommand(Tcl ignored, List<Object> args) {
        var options = new LinkedHashMap<Object, Object>();
        long code = 0;
        long level = 1;
        var at = 0;
        while (at + 1 < args.size() && Values.string(args.get(at)).startsWith("-")) {
            var option = Values.string(args.get(at++));
            var value = args.get(at++);
            if (option.equals("-options")) options.putAll(Values.dict(value));
            else options.put(option, value);
        }
        if (args.size() - at > 1) wrongArgs("return ?option value ...? ?result?");
        var result = at < args.size() ? args.get(at) : "";
        if (options.containsKey("-code")) code = returnCode(options.get("-code"));
        if (options.containsKey("-level")) level = Values.integer(options.get("-level"));
        if (level < 0) throw error("bad -level value: must be non-negative");
        options.put("-level", level);
        options.put("-code", code);
        if (level > 0) throw new TclException.Return(result, options);
        throw completion(result, code, options);
    }

    private Object errorCommand(Tcl ignored, List<Object> args) {
        arity("error", args, 1, 3);
        var options = new LinkedHashMap<Object, Object>();
        if (args.size() > 1) {
            options.put("-errorinfo", Values.string(args.get(1)));
            options.put("-tuul-notrace", true);
        }
        if (args.size() > 2) options.put("-errorcode", Values.list(args.get(2)));
        throw new TclException.Error(Values.string(args.getFirst()), options, null);
    }

    private Object throwCommand(Tcl ignored, List<Object> args) {
        arity("throw", args, 2, 2);
        throw new TclException.Error(Values.string(args.get(1)), Map.of("-errorcode", Values.list(args.getFirst())), null);
    }

    private Object catchCommand(Tcl ignored, List<Object> args) {
        arity("catch", args, 1, 3);
        Object result;
        Map<Object, Object> options;
        long code;
        try {
            result = runBody(args.getFirst(), 0);
            code = 0;
            options = options(0, 0);
        } catch (TclException completion) {
            result = completion.result();
            code = completion.code();
            options = completion.options();
            if (code != 2) options.put("-level", 0L);
            remember(completion);
        }
        if (args.size() > 1) set(variable(Values.string(args.get(1)), true), result);
        if (args.size() > 2) set(variable(Values.string(args.get(2)), true), options);
        return code;
    }

    private Object tryCommand(Tcl ignored, List<Object> args) {
        if (args.isEmpty()) wrongArgs("try body ?handler ...? ?finally script?");
        var body = outcome(() -> runBody(args.getFirst(), 0));
        var at = 1;
        Object finallyBody = null;
        var selected = -1;
        var handlerBody = -1;
        while (at < args.size()) {
            var kind = Values.string(args.get(at));
            if (kind.equals("finally")) {
                if (at + 2 != args.size()) wrongArgs("try body ?handler ...? ?finally script?");
                finallyBody = args.get(at + 1);
                break;
            }
            if (at + 3 >= args.size() || !(kind.equals("on") || kind.equals("trap"))) {
                wrongArgs("try body ?handler ...? ?finally script?");
            }
            var match = kind.equals("on")
                    ? body.code == returnCode(args.get(at + 1))
                    : body.code == 1 && prefix(Values.list(args.get(at + 1)), errorCode(body));
            if (selected < 0 && match) {
                selected = at;
                handlerBody = at + 3;
            }
            at += 4;
        }
        var result = body;
        if (selected >= 0) {
            while (handlerBody + 4 < args.size() && Values.string(args.get(handlerBody)).equals("-")) handlerBody += 4;
            var variables = Values.list(args.get(selected + 2));
            if (variables.size() > 2) throw error("handler variable list must contain at most two names");
            if (!variables.isEmpty()) set(variable(Values.string(variables.get(0)), true), body.result);
            if (variables.size() > 1) set(variable(Values.string(variables.get(1)), true), body.options);
            var bodyIndex = handlerBody;
            result = outcome(() -> runBody(args.get(bodyIndex), bodyIndex));
            if (result.code != 0) result.options.put("-during", body.options);
        }
        if (finallyBody != null) {
            var script = finallyBody;
            var finalResult = outcome(() -> runBody(script));
            if (finalResult.code != 0) {
                finalResult.options.put("-during", result.options);
                result = finalResult;
            }
        }
        if (result.code == 0) return result.result;
        throw completion(result.result, result.code, result.options);
    }

    private Object uplevelCommand(Tcl ignored, List<Object> args) {
        if (args.isEmpty()) wrongArgs("uplevel ?level? arg ?arg ...?");
        var at = levelLike(args.getFirst()) ? 1 : 0;
        var target = level(at == 1 ? args.getFirst() : 1L);
        if (at >= args.size()) wrongArgs("uplevel ?level? arg ?arg ...?");
        var source = join(args.subList(at, args.size()));
        var current = frame();
        frames.add(new Frame(target.namespace, target.variables, target.proc, target.procName, target.call, target.procFirstLine));
        try {
            return run(Script.parse(source), "eval");
        } catch (TclException.Error failure) {
            var options = failure.options();
            var stack = new ArrayList<>(Values.list(options.get("-errorstack")));
            stack.add("UP");
            stack.add((long) Math.max(0, frames.size() - 2 - frames.indexOf(target)));
            options.put("-errorstack", stack);
            throw new TclException.Error(failure.result(), options, failure.getCause());
        } finally {
            frames.removeLast();
        }
    }

    private Object upvarCommand(Tcl ignored, List<Object> args) {
        if (args.size() < 2) wrongArgs("upvar ?level? other local ?other local ...?");
        var at = levelLike(args.getFirst()) && (args.size() & 1) == 1 ? 1 : 0;
        var target = level(at == 1 ? args.getFirst() : 1L);
        if (((args.size() - at) & 1) != 0) wrongArgs("upvar ?level? other local ?other local ...?");
        while (at < args.size()) {
            link(target, Values.string(args.get(at)), Values.string(args.get(at + 1)));
            at += 2;
        }
        return "";
    }

    private Object globalCommand(Tcl ignored, List<Object> args) {
        if (args.isEmpty()) wrongArgs("global name ?name ...?");
        if (!frame().proc && frame().variables == root.variables) return "";
        for (var name : args) link(frames.getFirst(), "::" + Values.string(name), tail(Values.string(name)));
        return "";
    }

    private Object variableCommand(Tcl ignored, List<Object> args) {
        if (args.isEmpty()) wrongArgs("variable name ?value? ?name value ...?");
        var at = 0;
        while (at < args.size()) {
            var name = Values.string(args.get(at++));
            var valuePresent = at < args.size();
            var value = valuePresent ? args.get(at++) : null;
            if (frame().proc) {
                var targetName = variableTarget(base(name), frame().namespace, false);
                if (targetName == null) throw error("unknown namespace for variable \"" + name + "\"");
                var target = new VarRef(targetName.namespace.variables, targetName.tail, index(name));
                declare(target);
                frame().variables.put(tail(name), new Link(target));
                if (valuePresent && !exists(target)) set(target, value);
            } else {
                var ref = variable(name, true);
                declare(ref);
                if (valuePresent) set(ref, value);
            }
        }
        return "";
    }

    private Object runBody(Object value) {
        return runBody(value, -1);
    }

    private Object runBody(Object value, int argumentIndex) {
        var script = argumentIndex < 0 ? Values.script(value) : scriptArgument(value, argumentIndex);
        var origin = evaluations.isEmpty() ? script.origin() : evaluations.getLast().origin;
        if (script.origin().isEmpty() && !origin.isEmpty()) script = new Script(script.commands(), origin);
        return run(script, script.origin().isEmpty() ? "eval" : "source");
    }

    private Script scriptArgument(Object value, int argumentIndex) {
        if (value instanceof String text && !evaluations.isEmpty()) {
            var evaluation = evaluations.getLast();
            var wordIndex = argumentIndex + 1;
            if (wordIndex < evaluation.command.words().size()) {
                var word = evaluation.command.words().get(wordIndex);
                if (word.braced() && word.body().equals(text)) {
                    var firstLine = evaluation.origin.isEmpty() ? 1 : word.line();
                    return Script.parse(text, firstLine, evaluation.origin);
                }
            }
        }
        return Values.script(value);
    }

    private int argumentLine(int argumentIndex) {
        if (evaluations.isEmpty()) return 1;
        var evaluation = evaluations.getLast();
        if (evaluation.origin.isEmpty()) return 1;
        var wordIndex = argumentIndex + 1;
        return wordIndex < evaluation.command.words().size() ? evaluation.command.words().get(wordIndex).line() : 1;
    }

    private boolean test(Object value) {
        if (value instanceof Boolean || value instanceof Number || value == null) return Values.bool(value);
        return Values.bool(new Expr(this, Values.string(value)).parse());
    }

    private void enter() {
        var thread = Thread.currentThread();
        synchronized (gate) {
            if (owner != null && owner != thread) throw error("interpreter is busy", "TCL", "BUSY");
            owner = thread;
            entries++;
        }
        CURRENT.get().addLast(this);
    }

    private void leave() {
        var stack = CURRENT.get();
        stack.removeLast();
        if (stack.isEmpty()) CURRENT.remove();
        synchronized (gate) {
            if (--entries == 0) owner = null;
        }
    }

    private <T> T locked(java.util.function.Supplier<T> operation) {
        enter();
        try {
            return operation.get();
        } finally {
            leave();
        }
    }

    private Object finishAtBoundary(TclException.Return completion) {
        var options = completion.options();
        var level = Values.integer(options.get("-level"));
        if (level > 1) {
            options.put("-level", level - 1);
            throw new TclException.Return(completion.result(), options);
        }
        var code = returnCode(options.get("-code"));
        return code == 0 ? completion.result() : throwCompletion(completion.result(), code, options);
    }

    private Object throwCompletion(Object result, long code, Map<?, ?> options) {
        throw completion(result, code, options);
    }

    private static TclException completion(Object result, long code, Map<?, ?> options) {
        return switch ((int) code) {
            case 1 -> new TclException.Error(result, options, null);
            case 2 -> new TclException.Return(result, options);
            case 3 -> new TclException.Break(result, options);
            case 4 -> new TclException.Continue(result, options);
            default -> new TclException.Code(result, code, options);
        };
    }

    private static long returnCode(Object value) {
        return switch (Values.string(value)) {
            case "ok" -> 0;
            case "error" -> 1;
            case "return" -> 2;
            case "break" -> 3;
            case "continue" -> 4;
            default -> Values.integer(value);
        };
    }

    private static TclException.Error error(String message, Object... code) {
        return new TclException.Error(message, code.length == 0 ? List.of("NONE") : List.of(code));
    }

    private static TclException.Error missingVariable(String name) {
        return error("can't read \"" + name + "\": no such variable", "TCL", "LOOKUP", "VARNAME", name);
    }

    private static void wrongArgs(String usage) {
        throw error("wrong # args: should be \"" + usage + "\"", "TCL", "WRONGARGS");
    }

    private static void arity(String command, List<?> args, int minimum, int maximum) {
        if (args.size() < minimum || args.size() > maximum) wrongArgs(command);
    }

    private static Map<Object, Object> options(long code, long level) {
        return new LinkedHashMap<>(Map.of("-code", code, "-level", level));
    }

    private Frame frame() {
        return frames.getLast();
    }

    private record Evaluation(Script.Command command, String origin, String type) {}

    private record Outcome(Object result, long code, LinkedHashMap<Object, Object> options) {}

    private Outcome outcome(java.util.function.Supplier<Object> action) {
        try {
            return new Outcome(action.get(), 0, new LinkedHashMap<>(options(0, 0)));
        } catch (TclException completion) {
            return new Outcome(completion.result(), completion.code(), new LinkedHashMap<>(completion.options()));
        }
    }

    private void remember(TclException completion) {
        if (completion.code() != 1) return;
        var options = completion.options();
        lastErrorStack = Values.list(options.get("-errorstack"));
        root.variables.put("errorInfo", new Scalar(options.get("-errorinfo"), true));
        root.variables.put("errorCode", new Scalar(options.get("-errorcode"), true));
    }

    private TclException.Error trace(TclException.Error failure, Script.Command command, String origin, List<Object> argv) {
        var options = failure.options();
        var info = Values.string(options.get("-errorinfo"));
        var stack = Values.list(options.get("-errorstack"));
        if (stack.isEmpty()) {
            var inner = argv == null ? command.words().stream().map(Word::source).map(Object.class::cast).toList() : argv;
            stack = new ArrayList<>(List.of("INNER", inner));
            options.put("-errorstack", stack);
            options.put("-errorline", (long) command.line());
        }
        var skip = Values.bool(options.getOrDefault("-tuul-notrace", false));
        options.remove("-tuul-notrace");
        if (!skip && !info.contains("\n    while executing\n\"" + command.source() + "\"")) {
            info += "\n    while executing\n\"" + command.source() + "\"";
            if (frame().proc) {
                var line = Math.max(1, command.line() - frame().procFirstLine + 1);
                info += "\n    (procedure \"" + frame().procName + "\" line " + line + ")";
            } else if (!origin.isEmpty()) info += "\n    (file \"" + origin + "\" line " + command.line() + ")";
            options.put("-errorinfo", info);
        }
        return new TclException.Error(failure.result(), options, failure.getCause());
    }

    private static boolean prefix(List<Object> prefix, List<Object> value) {
        if (prefix.size() > value.size()) return false;
        for (var at = 0; at < prefix.size(); at++) if (!Objects.equals(prefix.get(at), value.get(at))) return false;
        return true;
    }

    private static List<Object> errorCode(Outcome outcome) {
        return outcome.code == 1 ? Values.list(outcome.options.get("-errorcode")) : List.of();
    }

    private static String join(List<?> values) {
        return values.stream().map(Values::string).reduce((a, b) -> a + " " + b).orElse("");
    }

    private static boolean levelLike(Object value) {
        var text = Values.string(value);
        return text.startsWith("#") || text.matches("-?[0-9]+");
    }

    private Frame level(Object value) {
        var text = Values.string(value);
        int index;
        if (text.startsWith("#")) index = Math.toIntExact(Values.integer(text.substring(1)));
        else index = frames.size() - 1 - Math.toIntExact(Values.integer(value));
        if (index < 0 || index >= frames.size()) throw error("bad level \"" + text + "\"");
        return frames.get(index);
    }

    private static boolean matches(String mode, boolean nocase, String pattern, String value) {
        return switch (mode) {
            case "exact" -> nocase ? pattern.equalsIgnoreCase(value) : pattern.equals(value);
            case "glob" -> Glob.matches(pattern, value, nocase);
            case "regexp" -> Pattern.compile(pattern, nocase ? Pattern.CASE_INSENSITIVE : 0).matcher(value).find();
            default -> false;
        };
    }

    private Iterator<List<Object>> collection(Object collection, int width) {
        if (collection instanceof Map<?, ?> map) {
            var entries = map.entrySet().stream().map(entry -> List.of(entry.getKey(), entry.getValue())).toList();
            return entries.iterator();
        }
        final Iterator<?> iterator;
        if (collection instanceof Iterable<?> iterable) iterator = iterable.iterator();
        else if (collection instanceof Iterator<?> found) iterator = found;
        else if (collection instanceof Stream<?> stream) iterator = stream.iterator();
        else if (collection != null && collection.getClass().isArray()) {
            var values = new ArrayList<>();
            for (var at = 0; at < Array.getLength(collection); at++) values.add(Array.get(collection, at));
            iterator = values.iterator();
        } else if (collection instanceof String text) iterator = Values.list(text).iterator();
        else throw error("expected an iterable collection");
        return new Iterator<>() {
            @Override public boolean hasNext() { return iterator.hasNext(); }
            @Override public List<Object> next() {
                var group = new ArrayList<Object>(width);
                while (group.size() < width && iterator.hasNext()) group.add(iterator.next());
                return group;
            }
        };
    }

    private List<Parameter> parseParameters(Object specification) {
        var words = Values.list(specification);
        var result = new ArrayList<Parameter>();
        for (var at = 0; at < words.size(); at++) {
            var parameter = Values.list(words.get(at));
            if (parameter.size() < 1 || parameter.size() > 2) throw error("too many fields in argument specifier");
            var name = Values.string(parameter.getFirst());
            var variadic = name.equals("args") && at == words.size() - 1 && parameter.size() == 1;
            result.add(new Parameter(name, parameter.size() == 2 ? parameter.get(1) : null, parameter.size() == 2, variadic));
        }
        return result;
    }

    private record Parameter(String name, Object defaultValue, boolean optional, boolean variadic) {}

    private final class Proc implements Command {

        private final String name;
        private final Namespace namespace;
        private final List<Parameter> parameters;
        private final Object bodySource;
        private final Script body;
        private final int bodyFirstLine;

        private Proc(String name, Namespace namespace, List<Parameter> parameters, Object bodySource, Script body,
                int bodyFirstLine) {
            this.name = name;
            this.namespace = namespace;
            this.parameters = List.copyOf(parameters);
            this.bodySource = bodySource;
            this.body = body;
            this.bodyFirstLine = bodyFirstLine;
        }

        @Override
        public Object call(Tcl tcl, List<Object> args) {
            var variables = new LinkedHashMap<String, Slot>();
            var at = 0;
            for (var parameter : parameters) {
                if (parameter.variadic) {
                    variables.put(parameter.name, new Scalar(new ArrayList<>(args.subList(at, args.size())), true));
                    at = args.size();
                    continue;
                }
                Object value = null;
                if (at < args.size()) value = args.get(at++);
                else if (parameter.optional) value = runBody(parameter.defaultValue);
                else wrongArgs(name + " " + parameters.stream().map(Parameter::name).reduce((a, b) -> a + " " + b).orElse(""));
                variables.put(parameter.name, new Scalar(value, true));
            }
            if (at != args.size()) wrongArgs(name + " " + parameters.stream().map(Parameter::name).reduce((a, b) -> a + " " + b).orElse(""));
            var call = new ArrayList<Object>();
            call.add(name);
            call.addAll(args);
            frames.add(new Frame(namespace, variables, true, name, call, bodyFirstLine));
            try {
                return runBody(body);
            } catch (TclException.Return completion) {
                var options = completion.options();
                var level = Values.integer(options.get("-level")) - 1;
                options.put("-level", level);
                if (level > 0) throw new TclException.Return(completion.result(), options);
                var code = returnCode(options.get("-code"));
                if (code == 0) return completion.result();
                throw completion(completion.result(), code, options);
            } catch (TclException.Error failure) {
                var options = failure.options();
                var stack = new ArrayList<>(Values.list(options.get("-errorstack")));
                stack.add("CALL");
                stack.add(call);
                options.put("-errorstack", stack);
                throw new TclException.Error(failure.result(), options, failure.getCause());
            } finally {
                frames.removeLast();
            }
        }
    }

    private static final class Frame {

        private final Namespace namespace;
        private final Map<String, Slot> variables;
        private final boolean proc;
        private final String procName;
        private final List<Object> call;
        private final int procFirstLine;

        private Frame(Namespace namespace, Map<String, Slot> variables, boolean proc, String procName, List<Object> call) {
            this(namespace, variables, proc, procName, call, 1);
        }

        private Frame(Namespace namespace, Map<String, Slot> variables, boolean proc, String procName, List<Object> call,
                int procFirstLine) {
            this.namespace = namespace;
            this.variables = variables;
            this.proc = proc;
            this.procName = procName;
            this.call = call;
            this.procFirstLine = procFirstLine;
        }
    }

    private sealed interface Slot permits Scalar, ArraySlot, Link {}
    private record Scalar(Object value, boolean set) implements Slot {}
    private record ArraySlot(LinkedHashMap<String, Scalar> elements) implements Slot {}
    private record Link(VarRef target) implements Slot {}
    private record VarRef(Map<String, Slot> table, String name, String index) {}

    private VarRef variable(String name, boolean writing) {
        var index = index(name);
        var base = base(name);
        if (!base.contains("::")) return new VarRef(frame().variables, base, index);
        var target = variableTarget(base, frame().namespace, false);
        if (target == null) throw error("parent namespace does not exist for variable \"" + name + "\"");
        return new VarRef(target.namespace.variables, target.tail, index);
    }

    private VarRef variableOrNull(String name) {
        try {
            return variable(name, false);
        } catch (TclException.Error failure) {
            return null;
        }
    }

    private VarRef javaVariable(String name, boolean create) {
        var index = index(name);
        var target = variableTarget(base(name), root, create);
        if (target == null) throw missingVariable(name);
        return new VarRef(target.namespace.variables, target.tail, index);
    }

    private VarRef javaVariableOrNull(String name) {
        var target = variableTarget(base(name), root, false);
        return target == null ? null : new VarRef(target.namespace.variables, target.tail, index(name));
    }

    private Object get(VarRef original, String written) {
        var ref = dereference(original, new LinkedHashSet<>());
        var slot = ref.table.get(ref.name);
        if (ref.index != null) {
            if (!(slot instanceof ArraySlot array)) throw missingVariable(written);
            var element = array.elements.get(ref.index);
            if (element == null || !element.set) throw missingVariable(written);
            return element.value;
        }
        if (!(slot instanceof Scalar scalar) || !scalar.set) throw missingVariable(written);
        return scalar.value;
    }

    private boolean exists(VarRef original) {
        var ref = dereference(original, new LinkedHashSet<>());
        var slot = ref.table.get(ref.name);
        if (ref.index != null) return slot instanceof ArraySlot array
                && array.elements.get(ref.index) instanceof Scalar scalar && scalar.set;
        return slot instanceof Scalar scalar && scalar.set || slot instanceof ArraySlot;
    }

    private boolean declared(VarRef original) {
        var ref = dereference(original, new LinkedHashSet<>());
        var slot = ref.table.get(ref.name);
        if (ref.index == null) return slot != null;
        return slot instanceof ArraySlot array && array.elements.containsKey(ref.index);
    }

    private void set(VarRef original, Object value) {
        var ref = dereference(original, new LinkedHashSet<>());
        var slot = ref.table.get(ref.name);
        if (ref.index != null) {
            if (slot instanceof Scalar) throw error("can't set \"" + ref.name + "(" + ref.index + ")\": variable isn't array");
            var array = slot instanceof ArraySlot found ? found : new ArraySlot(new LinkedHashMap<>());
            array.elements.put(ref.index, new Scalar(value, true));
            ref.table.put(ref.name, array);
            return;
        }
        if (slot instanceof ArraySlot) throw error("can't set \"" + ref.name + "\": variable is array");
        ref.table.put(ref.name, new Scalar(value, true));
    }

    private void declare(VarRef original) {
        var ref = dereference(original, new LinkedHashSet<>());
        ref.table.putIfAbsent(ref.name, new Scalar(null, false));
    }

    private void unset(VarRef original) {
        var ref = dereference(original, new LinkedHashSet<>());
        if (ref.index == null) ref.table.remove(ref.name);
        else if (ref.table.get(ref.name) instanceof ArraySlot array) array.elements.remove(ref.index);
    }

    private VarRef dereference(VarRef ref, Set<LinkKey> seen) {
        while (ref.table.get(ref.name) instanceof Link link) {
            var key = new LinkKey(ref.table, ref.name);
            if (!seen.add(key)) throw error("variable link cycle");
            var target = link.target;
            ref = new VarRef(target.table, target.name, ref.index == null ? target.index : ref.index);
        }
        return ref;
    }

    private record LinkKey(Map<String, Slot> table, String name) {
        @Override public boolean equals(Object other) { return other instanceof LinkKey key && table == key.table && name.equals(key.name); }
        @Override public int hashCode() { return System.identityHashCode(table) * 31 + name.hashCode(); }
    }

    private void link(Frame targetFrame, String other, String local) {
        var target = other.contains("::") ? variable(other, true)
                : new VarRef(targetFrame.variables, base(other), index(other));
        var localRef = new VarRef(frame().variables, base(local), index(local));
        if (localRef.index != null) throw error("upvar local name must not be an array element");
        if (target.table == localRef.table && target.name.equals(localRef.name)) throw error("variable link cycle");
        localRef.table.put(localRef.name, new Link(target));
    }

    private static String index(String name) {
        var open = name.lastIndexOf('(');
        return open >= 0 && name.endsWith(")") ? name.substring(open + 1, name.length() - 1) : null;
    }

    private static String base(String name) {
        var open = name.lastIndexOf('(');
        return open >= 0 && name.endsWith(")") ? name.substring(0, open) : name;
    }

    private static final class Namespace {

        private final String tail;
        private final Namespace parent;
        private final LinkedHashMap<String, Namespace> children = new LinkedHashMap<>();
        private final LinkedHashMap<String, CommandRef> commands = new LinkedHashMap<>();
        private final LinkedHashMap<String, Slot> variables = new LinkedHashMap<>();
        private final ArrayList<Namespace> path = new ArrayList<>();
        private final ArrayList<String> exports = new ArrayList<>();
        private Object unknown;

        private Namespace(String tail, Namespace parent) {
            this.tail = tail;
            this.parent = parent;
        }
    }

    private record CommandRef(Command command, Namespace origin, String originalName, String kind, boolean imported) {}
    private record NameTarget(Namespace namespace, String tail) {}

    private static CommandRef ref(Command command, Namespace origin, String originalName, String kind) {
        return new CommandRef(command, origin, originalName, kind, false);
    }

    private void builtin(String name, Command command) {
        root.commands.put(name, ref(command, root, "::" + name, "native"));
    }

    private void namespaceCommand(Namespace namespace, String name, Command command) {
        namespace.commands.put(name, ref(command, namespace, fullName(namespace, name), "native"));
    }

    private void register(String name, Command command, String kind, boolean create) {
        var target = target(root, name, create);
        if (target == null) throw error("namespace does not exist for command \"" + name + "\"");
        target.namespace.commands.put(target.tail, ref(command, target.namespace, fullName(target.namespace, target.tail), kind));
    }

    private Namespace namespace(Namespace start, String name, boolean create) {
        var current = absolute(name) ? root : start;
        for (var part : parts(name)) {
            var next = current.children.get(part);
            if (next == null && create) {
                next = new Namespace(part, current);
                current.children.put(part, next);
            }
            if (next == null) return null;
            current = next;
        }
        return current;
    }

    private NameTarget target(Namespace start, String name, boolean create) {
        var parts = parts(name);
        if (parts.isEmpty()) return null;
        var parentName = String.join("::", parts.subList(0, parts.size() - 1));
        if (absolute(name)) parentName = "::" + parentName;
        var parent = parentName.isEmpty() ? (absolute(name) ? root : start) : namespace(start, parentName, create);
        return parent == null ? null : new NameTarget(parent, parts.getLast());
    }

    private NameTarget commandTarget(String name, boolean create) {
        return target(frame().namespace, name, create);
    }

    private NameTarget variableTarget(String name, Namespace start, boolean create) {
        if (!name.contains("::")) return new NameTarget(start, name);
        return target(start, name, create);
    }

    private CommandRef commandRef(String name, Namespace start) {
        if (name.contains("::")) {
            var target = target(start, name, false);
            return target == null ? null : target.namespace.commands.get(target.tail);
        }
        var found = start.commands.get(name);
        if (found != null) return found;
        for (var path : start.path) {
            found = path.commands.get(name);
            if (found != null) return found;
        }
        return start == root ? null : root.commands.get(name);
    }

    private static boolean absolute(String name) {
        return name.startsWith("::") || name.startsWith(":");
    }

    private static List<String> parts(String name) {
        return Pattern.compile(":+").splitAsStream(name).filter(part -> !part.isEmpty()).toList();
    }

    private static String fullName(Namespace namespace) {
        if (namespace.parent == null) return "::";
        var parts = new ArrayDeque<String>();
        for (var current = namespace; current.parent != null; current = current.parent) parts.addFirst(current.tail);
        return "::" + String.join("::", parts);
    }

    private static String fullName(Namespace namespace, String tail) {
        return namespace.parent == null ? "::" + tail : fullName(namespace) + "::" + tail;
    }

    private static String tail(String name) {
        var parts = parts(name);
        return parts.isEmpty() ? "" : parts.getLast();
    }

    private Object namespaceEval(Tcl ignored, List<Object> args) {
        if (args.size() < 2) wrongArgs("namespace eval name arg ?arg ...?");
        var target = namespace(frame().namespace, Values.string(args.getFirst()), true);
        var source = join(args.subList(1, args.size()));
        frames.add(new Frame(target, target.variables, false, "", List.of()));
        try {
            return run(Script.parse(source), "eval");
        } finally {
            frames.removeLast();
        }
    }

    private Object namespaceCurrent(Tcl ignored, List<Object> args) {
        arity("namespace current", args, 0, 0);
        return fullName(frame().namespace);
    }

    private Object namespaceParent(Tcl ignored, List<Object> args) {
        arity("namespace parent", args, 0, 1);
        var found = args.isEmpty() ? frame().namespace : namespace(frame().namespace, Values.string(args.getFirst()), false);
        if (found == null) throw error("unknown namespace \"" + Values.string(args.getFirst()) + "\"");
        return found.parent == null ? "" : fullName(found.parent);
    }

    private Object namespaceChildren(Tcl ignored, List<Object> args) {
        arity("namespace children", args, 0, 2);
        var found = args.isEmpty() ? frame().namespace : namespace(frame().namespace, Values.string(args.getFirst()), false);
        if (found == null) throw error("unknown namespace");
        var pattern = args.size() > 1 ? Values.string(args.get(1)) : "*";
        return found.children.values().stream().map(Tcl::fullName)
                .filter(name -> Glob.matches(pattern, name, false) || Glob.matches(pattern, tail(name), false)).toList();
    }

    private Object namespaceExists(Tcl ignored, List<Object> args) {
        arity("namespace exists", args, 1, 1);
        return namespace(frame().namespace, Values.string(args.getFirst()), false) != null ? 1L : 0L;
    }

    private Object namespaceDelete(Tcl ignored, List<Object> args) {
        if (args.isEmpty()) wrongArgs("namespace delete name ?name ...?");
        for (var value : args) {
            var found = namespace(frame().namespace, Values.string(value), false);
            if (found == null) continue;
            if (found == root) throw error("cannot delete the root namespace");
            for (var active : frames) {
                for (var current = active.namespace; current != null; current = current.parent) {
                    if (current == found) throw error("cannot delete an active namespace");
                }
            }
            found.parent.children.remove(found.tail);
        }
        return "";
    }

    private Object namespaceTail(Tcl ignored, List<Object> args) {
        arity("namespace tail", args, 1, 1);
        return tail(Values.string(args.getFirst()));
    }

    private Object namespaceQualifiers(Tcl ignored, List<Object> args) {
        arity("namespace qualifiers", args, 1, 1);
        var name = Values.string(args.getFirst());
        var components = parts(name);
        if (components.size() < 2) return "";
        var prefix = String.join("::", components.subList(0, components.size() - 1));
        return absolute(name) ? "::" + prefix : prefix;
    }

    private Object namespaceWhich(Tcl ignored, List<Object> args) {
        arity("namespace which", args, 1, 2);
        var option = args.size() == 2 ? Values.string(args.getFirst()) : "-command";
        var name = Values.string(args.getLast());
        if (option.equals("-command")) {
            var found = commandRef(name, frame().namespace);
            if (found == null) return "";
            if (name.contains("::")) {
                var target = target(frame().namespace, name, false);
                return target == null ? "" : fullName(target.namespace, target.tail);
            }
            if (frame().namespace.commands.containsKey(name)) return fullName(frame().namespace, name);
            for (var path : frame().namespace.path) if (path.commands.containsKey(name)) return fullName(path, name);
            return "::" + name;
        }
        if (!option.equals("-variable")) throw error("bad option \"" + option + "\"");
        var ref = variableOrNull(name);
        if (ref == null || !declared(ref)) return "";
        if (name.contains("::")) {
            var target = variableTarget(base(name), frame().namespace, false);
            return fullName(target.namespace, target.tail);
        }
        return frame().proc ? name : fullName(frame().namespace, name);
    }

    private Object namespaceOrigin(Tcl ignored, List<Object> args) {
        arity("namespace origin", args, 1, 1);
        var found = commandRef(Values.string(args.getFirst()), frame().namespace);
        if (found == null) throw error("invalid command name \"" + Values.string(args.getFirst()) + "\"");
        return found.originalName;
    }

    private Object namespaceExport(Tcl ignored, List<Object> args) {
        var at = 0;
        if (!args.isEmpty() && Values.string(args.getFirst()).equals("-clear")) {
            frame().namespace.exports.clear();
            at++;
        }
        for (; at < args.size(); at++) frame().namespace.exports.add(Values.string(args.get(at)));
        return new ArrayList<>(frame().namespace.exports);
    }

    private Object namespaceImport(Tcl ignored, List<Object> args) {
        var force = !args.isEmpty() && Values.string(args.getFirst()).equals("-force");
        var at = force ? 1 : 0;
        if (at == args.size()) wrongArgs("namespace import ?-force? pattern ?pattern ...?");
        for (; at < args.size(); at++) {
            var specification = Values.string(args.get(at));
            var qualifier = namespaceQualifiers(this, List.of(specification));
            var source = namespace(frame().namespace, Values.string(qualifier), false);
            if (source == null) throw error("unknown namespace in import pattern");
            var pattern = tail(specification);
            for (var entry : source.commands.entrySet()) {
                if (!Glob.matches(pattern, entry.getKey(), false)) continue;
                if (source.exports.stream().noneMatch(export -> Glob.matches(export, entry.getKey(), false))) continue;
                if (!force && frame().namespace.commands.containsKey(entry.getKey())) throw error("command already exists: " + entry.getKey());
                var ref = entry.getValue();
                frame().namespace.commands.put(entry.getKey(), new CommandRef(ref.command, ref.origin, ref.originalName, "import", true));
            }
        }
        return "";
    }

    private Object namespaceForget(Tcl ignored, List<Object> args) {
        if (args.isEmpty()) wrongArgs("namespace forget name ?name ...?");
        for (var value : args) {
            var pattern = tail(Values.string(value));
            frame().namespace.commands.entrySet().removeIf(entry -> entry.getValue().imported && Glob.matches(pattern, entry.getKey(), false));
        }
        return "";
    }

    private Object namespaceUpvar(Tcl ignored, List<Object> args) {
        if (args.size() < 3 || (args.size() & 1) == 0) wrongArgs("namespace upvar ns other local ?other local ...?");
        var target = namespace(frame().namespace, Values.string(args.getFirst()), false);
        if (target == null) throw error("unknown namespace");
        for (var at = 1; at < args.size(); at += 2) {
            var ref = new VarRef(target.variables, base(Values.string(args.get(at))), index(Values.string(args.get(at))));
            frame().variables.put(Values.string(args.get(at + 1)), new Link(ref));
        }
        return "";
    }

    private Object namespacePath(Tcl ignored, List<Object> args) {
        arity("namespace path", args, 0, 1);
        if (args.isEmpty()) return frame().namespace.path.stream().map(Tcl::fullName).toList();
        var next = new ArrayList<Namespace>();
        for (var value : Values.list(args.getFirst())) {
            var found = namespace(frame().namespace, Values.string(value), false);
            if (found == null) throw error("unknown namespace \"" + Values.string(value) + "\"");
            next.add(found);
        }
        frame().namespace.path.clear();
        frame().namespace.path.addAll(next);
        return "";
    }

    private Object namespaceUnknown(Tcl ignored, List<Object> args) {
        arity("namespace unknown", args, 0, 1);
        if (args.isEmpty()) return frame().namespace.unknown == null ? "" : frame().namespace.unknown;
        frame().namespace.unknown = Values.string(args.getFirst()).isEmpty() ? null : args.getFirst();
        return args.getFirst();
    }

    private Object namespaceCode(Tcl ignored, List<Object> args) {
        arity("namespace code", args, 1, 1);
        return List.of("::namespace::inscope", fullName(frame().namespace), args.getFirst());
    }

    private Object namespaceInscope(Tcl ignored, List<Object> args) {
        if (args.size() < 2) wrongArgs("namespace inscope name arg ?arg ...?");
        var target = namespace(frame().namespace, Values.string(args.getFirst()), false);
        if (target == null) throw error("unknown namespace");
        var prefix = new ArrayList<>(Values.list(args.get(1)));
        prefix.addAll(args.subList(2, args.size()));
        frames.add(new Frame(target, target.variables, false, "", List.of()));
        try {
            return dispatch(prefix);
        } finally {
            frames.removeLast();
        }
    }

    private Object ensembleCreate(Tcl ignored, List<Object> args) {
        if ((args.size() & 1) != 0) wrongArgs("namespace ensemble create ?option value ...?");
        var ensemble = new Ensemble(frame().namespace, fullName(frame().namespace));
        ensemble.configure(args);
        var name = ensemble.commandName;
        var target = target(frame().namespace, name, true);
        target.namespace.commands.put(target.tail, ref(ensemble, frame().namespace, fullName(target.namespace, target.tail), "ensemble"));
        return fullName(target.namespace, target.tail);
    }

    private Object ensembleConfigure(Tcl ignored, List<Object> args) {
        if (args.isEmpty()) wrongArgs("namespace ensemble configure command ?option? ?value option value ...?");
        var found = commandRef(Values.string(args.getFirst()), frame().namespace);
        if (found == null || !(found.command instanceof Ensemble ensemble)) throw error("command is not an ensemble");
        if (args.size() == 1) return ensemble.options();
        if (args.size() == 2) return ensemble.option(Values.string(args.get(1)));
        ensemble.configure(args.subList(1, args.size()));
        return "";
    }

    private Object ensembleExists(Tcl ignored, List<Object> args) {
        arity("namespace ensemble exists", args, 1, 1);
        var found = commandRef(Values.string(args.getFirst()), frame().namespace);
        return found != null && found.command instanceof Ensemble ? 1L : 0L;
    }

    private final class Ensemble implements Command {

        private final Namespace namespace;
        private String commandName;
        private LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        private List<Object> subcommands = List.of();
        private boolean prefixes = true;
        private List<Object> parameters = List.of();
        private List<Object> unknown = List.of();

        private Ensemble(Namespace namespace, String commandName) {
            this.namespace = namespace;
            this.commandName = commandName;
        }

        @Override
        public Object call(Tcl tcl, List<Object> args) {
            var parameterCount = parameters.size();
            if (args.size() <= parameterCount) wrongArgs(commandName + " ?parameter ...? subcommand ?arg ...?");
            var suppliedParameters = new ArrayList<>(args.subList(0, parameterCount));
            var requested = Values.string(args.get(parameterCount));
            var names = names();
            var selected = names.stream().filter(requested::equals).findFirst().orElse(null);
            if (selected == null && prefixes) {
                var matches = names.stream().filter(name -> name.startsWith(requested)).toList();
                if (matches.size() == 1) selected = matches.getFirst();
                else if (matches.size() > 1) throw error("ambiguous subcommand \"" + requested + "\"");
            }
            if (selected == null) {
                if (!unknown.isEmpty()) {
                    var call = new ArrayList<Object>(unknown);
                    call.add(commandName);
                    call.addAll(args);
                    return dispatch(call);
                }
                throw error("unknown or ambiguous subcommand \"" + requested + "\"");
            }
            var call = new ArrayList<Object>();
            var mapped = map.get(selected);
            if (mapped != null) call.addAll(Values.list(mapped));
            else call.add(fullName(namespace, selected));
            call.addAll(suppliedParameters);
            call.addAll(args.subList(parameterCount + 1, args.size()));
            return dispatch(call);
        }

        private List<String> names() {
            if (!subcommands.isEmpty()) return subcommands.stream().map(Values::string).toList();
            if (!map.isEmpty()) return map.keySet().stream().map(Values::string).toList();
            return new ArrayList<>(namespace.commands.keySet());
        }

        private void configure(List<?> options) {
            if ((options.size() & 1) != 0) wrongArgs("namespace ensemble configure command ?option value ...?");
            for (var at = 0; at < options.size(); at += 2) {
                var option = Values.string(options.get(at));
                var value = options.get(at + 1);
                switch (option) {
                    case "-command" -> commandName = Values.string(value);
                    case "-map" -> map = new LinkedHashMap<>(Values.dict(value));
                    case "-subcommands" -> subcommands = Values.list(value);
                    case "-prefixes" -> prefixes = Values.bool(value);
                    case "-parameters" -> parameters = Values.list(value);
                    case "-unknown" -> unknown = Values.list(value);
                    default -> throw error("unknown ensemble option \"" + option + "\"");
                }
            }
        }

        private Object option(String option) {
            return switch (option) {
                case "-command" -> commandName;
                case "-map" -> map;
                case "-subcommands" -> subcommands;
                case "-prefixes" -> prefixes;
                case "-parameters" -> parameters;
                case "-unknown" -> unknown;
                default -> throw error("unknown ensemble option \"" + option + "\"");
            };
        }

        private Map<Object, Object> options() {
            var result = new LinkedHashMap<Object, Object>();
            for (var option : List.of("-command", "-map", "-subcommands", "-prefixes", "-parameters", "-unknown")) {
                result.put(option, option(option));
            }
            return result;
        }
    }

    private Object infoArgs(Tcl ignored, List<Object> args) {
        arity("info args", args, 1, 1);
        return proc(Values.string(args.getFirst())).parameters.stream().map(Parameter::name).toList();
    }

    private Object infoBody(Tcl ignored, List<Object> args) {
        arity("info body", args, 1, 1);
        return proc(Values.string(args.getFirst())).bodySource;
    }

    private Object infoCmdcount(Tcl ignored, List<Object> args) {
        arity("info cmdcount", args, 0, 0);
        return commandCount;
    }

    private Object infoCmdtype(Tcl ignored, List<Object> args) {
        arity("info cmdtype", args, 1, 1);
        var found = commandRef(Values.string(args.getFirst()), frame().namespace);
        if (found == null) throw error("invalid command name");
        return found.kind;
    }

    private Object infoCommands(Tcl ignored, List<Object> args) {
        arity("info commands", args, 0, 1);
        return visibleCommands(args.isEmpty() ? "*" : Values.string(args.getFirst()), false);
    }

    private Object infoComplete(Tcl ignored, List<Object> args) {
        arity("info complete", args, 1, 1);
        return Repl.complete(Values.string(args.getFirst())) ? 1L : 0L;
    }

    private Object infoDefault(Tcl ignored, List<Object> args) {
        arity("info default", args, 3, 3);
        var name = Values.string(args.get(1));
        var parameter = proc(Values.string(args.getFirst())).parameters.stream().filter(item -> item.name.equals(name)).findFirst()
                .orElseThrow(() -> error("procedure has no argument \"" + name + "\""));
        if (!parameter.optional) return 0L;
        set(variable(Values.string(args.get(2)), true), parameter.defaultValue);
        return 1L;
    }

    private Object infoErrorstack(Tcl ignored, List<Object> args) {
        arity("info errorstack", args, 0, 0);
        return lastErrorStack;
    }

    private Object infoExists(Tcl ignored, List<Object> args) {
        arity("info exists", args, 1, 1);
        var ref = variableOrNull(Values.string(args.getFirst()));
        return ref != null && exists(ref) ? 1L : 0L;
    }

    private Object infoFrame(Tcl ignored, List<Object> args) {
        arity("info frame", args, 0, 1);
        if (args.isEmpty()) return (long) evaluations.size();
        var depth = Math.toIntExact(Values.integer(args.getFirst()));
        var index = depth > 0 ? depth - 1 : evaluations.size() - 1 + depth;
        if (index < 0 || index >= evaluations.size()) throw error("bad frame depth");
        var evaluation = evaluations.get(index);
        var result = new LinkedHashMap<Object, Object>();
        result.put("type", evaluation.origin.isEmpty() ? (frame().proc ? "proc" : "eval") : "source");
        result.put("line", (long) evaluation.command.line());
        result.put("cmd", evaluation.command.source());
        if (!evaluation.origin.isEmpty()) result.put("file", evaluation.origin);
        if (frame().proc) {
            result.put("proc", frame().procName);
            result.put("level", (long) procFrames());
        }
        return result;
    }

    private Object infoFunctions(Tcl ignored, List<Object> args) {
        arity("info functions", args, 0, 1);
        var pattern = args.isEmpty() ? "*" : Values.string(args.getFirst());
        return List.of("int", "double", "abs", "min", "max", "round").stream()
                .filter(name -> Glob.matches(pattern, name, false)).toList();
    }

    private Object infoGlobals(Tcl ignored, List<Object> args) {
        arity("info globals", args, 0, 1);
        return variableNames(root.variables, args.isEmpty() ? "*" : Values.string(args.getFirst()));
    }

    private Object infoLevel(Tcl ignored, List<Object> args) {
        arity("info level", args, 0, 1);
        if (args.isEmpty()) return (long) procFrames();
        var number = Math.toIntExact(Values.integer(args.getFirst()));
        var procs = frames.stream().filter(item -> item.proc).toList();
        var index = number > 0 ? number - 1 : procs.size() - 1 + number;
        if (index < 0 || index >= procs.size()) throw error("bad level");
        return procs.get(index).call;
    }

    private Object infoLocals(Tcl ignored, List<Object> args) {
        arity("info locals", args, 0, 1);
        if (!frame().proc) return List.of();
        var pattern = args.isEmpty() ? "*" : Values.string(args.getFirst());
        return frame().variables.entrySet().stream().filter(entry -> !(entry.getValue() instanceof Link))
                .map(Map.Entry::getKey).filter(name -> Glob.matches(pattern, name, false)).toList();
    }

    private Object infoProcs(Tcl ignored, List<Object> args) {
        arity("info procs", args, 0, 1);
        return visibleCommands(args.isEmpty() ? "*" : Values.string(args.getFirst()), true);
    }

    private Object infoScript(Tcl ignored, List<Object> args) {
        arity("info script", args, 0, 1);
        if (evaluations.isEmpty()) return "";
        var at = evaluations.size() - 1;
        if (!args.isEmpty()) {
            var current = evaluations.get(at);
            evaluations.set(at, new Evaluation(current.command, Values.string(args.getFirst()), current.type));
        }
        return evaluations.get(at).origin;
    }

    private Object infoVars(Tcl ignored, List<Object> args) {
        arity("info vars", args, 0, 1);
        var pattern = args.isEmpty() ? "*" : Values.string(args.getFirst());
        if (pattern.contains("::")) {
            var qualifier = Values.string(namespaceQualifiers(this, List.of(pattern)));
            var selected = namespace(frame().namespace, qualifier, false);
            if (selected == null) return List.of();
            var tailPattern = tail(pattern);
            return selected.variables.keySet().stream().filter(name -> Glob.matches(tailPattern, name, false))
                    .map(name -> (Object) fullName(selected, name)).toList();
        }
        var result = new LinkedHashSet<Object>();
        result.addAll(variableNames(frame().variables, pattern));
        if (frame().proc) result.addAll(variableNames(frame().namespace.variables, pattern));
        return new ArrayList<>(result);
    }

    private Proc proc(String name) {
        var found = commandRef(name, frame().namespace);
        if (found == null || !(found.command instanceof Proc proc)) throw error("\"" + name + "\" isn't a procedure");
        return proc;
    }

    private List<Object> visibleCommands(String pattern, boolean onlyProcs) {
        if (pattern.contains("::")) {
            var qualifier = Values.string(namespaceQualifiers(this, List.of(pattern)));
            var namespace = namespace(frame().namespace, qualifier, false);
            if (namespace == null) return List.of();
            var tailPattern = tail(pattern);
            return namespace.commands.entrySet().stream()
                    .filter(entry -> !onlyProcs || entry.getValue().command instanceof Proc)
                    .filter(entry -> Glob.matches(tailPattern, entry.getKey(), false))
                    .map(entry -> (Object) fullName(namespace, entry.getKey())).toList();
        }
        var names = new LinkedHashMap<String, CommandRef>();
        names.putAll(root.commands);
        for (var path : frame().namespace.path) names.putAll(path.commands);
        names.putAll(frame().namespace.commands);
        return names.entrySet().stream().filter(entry -> !onlyProcs || entry.getValue().command instanceof Proc)
                .map(Map.Entry::getKey).filter(name -> Glob.matches(pattern, name, false)).map(Object.class::cast).toList();
    }

    private static List<Object> variableNames(Map<String, Slot> variables, String pattern) {
        return variables.keySet().stream().filter(name -> Glob.matches(pattern, name, false)).map(Object.class::cast).toList();
    }

    private int procFrames() {
        return (int) frames.stream().filter(item -> item.proc).count();
    }
}
