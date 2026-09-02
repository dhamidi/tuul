package tcl;

import harness.Check;
import json.Json;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class TclTest {

    private TclTest() {}

    public static void run() throws Exception {
        keepsJvmValues();
        parsesAndSubstitutes();
        evaluatesExpressionsAndControlFlow();
        definesProceduresAndLinksVariables();
        resolvesNamespacesAndEnsembles();
        carriesErrorsAndReturnOptions();
        callsJavaAndBuildsCallbacks();
        createsJavaObjects();
        importsClasses();
        testsTypes();
        streamsSourceCommands();
        rejectsConcurrentUse();
    }

    private static void keepsJvmValues() {
        var tcl = Tcl.of();
        var object = new Object();
        tcl.set("app::object", object).set("nothing", null);

        Check.that("a lone variable keeps its JVM object", tcl.eval("set x $::app::object") == object);
        Check.equal("interpolation converts an object to text", "object=" + object, tcl.eval("set x \"object=$::app::object\""));
        Check.that("a variable that holds null exists", tcl.exists("nothing"));
        Check.that("an absent variable does not exist", !tcl.exists("absent"));
    }

    private static void parsesAndSubstitutes() {
        var tcl = Tcl.of();
        Check.equal("braces prevent substitution", "$x [set x]", tcl.eval("set x 1; set y {$x [set x]}"));
        Check.equal("quotes permit substitution", "a 1 1 b", tcl.eval("set x 1; set y \"a $x [set x] b\""));
        Check.equal("escapes produce control characters", "a\nb", tcl.eval("set x a\\nb"));
        Check.equal("an array index substitutes", "value", tcl.eval("set i key; set a(key) value; set x $a($i)"));
        Check.equal("expanded list elements stay separate", List.of("a", "b", "c d"),
                tcl.eval("proc words {args} {return $args}; words {*}{a b {c d}}"));
        Check.equal("comments start only at command boundaries", "#", tcl.eval("set x #; # ignored {\nset x"));
    }

    private static void evaluatesExpressionsAndControlFlow() {
        var tcl = Tcl.of();
        Check.equal("integer arithmetic stays integral", 14L, tcl.eval("expr {2 + 3 * 4}"));
        Check.equal("integer division stays integral", 3L, tcl.eval("expr {7 / 2}"));
        Check.equal("conditional expressions short circuit", 1L, tcl.eval("expr {1 ? 1 : [error no]}"));
        Check.equal("while and continue update state", 12L, tcl.eval("""
                set sum 0
                set i 0
                while {$i < 5} {
                    incr i
                    if {$i == 3} {continue}
                    incr sum $i
                }
                set sum
                """));
        Check.equal("for runs its next script after continue", 4L, tcl.eval("""
                set seen 0
                for {set i 0} {$i < 4} {incr i} {
                    incr seen
                    continue
                }
                set seen
                """));
        Check.equal("foreach consumes a Tcl list", 6L,
                tcl.eval("set sum 0; foreach x {1 2 3} {incr sum $x}; set sum"));
        Check.equal("switch glob selects the first match", "yes",
                tcl.eval("switch -glob hello h* {set answer yes} default {set answer no}"));
    }

    private static void definesProceduresAndLinksVariables() {
        var tcl = Tcl.of();
        Check.equal("a proc returns through nested control flow", 10L, tcl.eval("""
                proc bump {times} {
                    set i 0
                    while {$i < $times} {incr i}
                    return $i
                }
                bump 10
                """));
        Check.equal("args receives remaining JVM values", List.of("b", "c"),
                tcl.eval("proc rest {first args} {return $args}; rest a b c"));
        Check.equal("global links a proc local to the root", 2L,
                tcl.eval("set x 1; proc add {} {global x; incr x}; add; set x"));
        Check.equal("upvar links to the selected frame", 3L, tcl.eval("""
                proc add {name} {upvar 1 $name value; incr value}
                proc use {} {set local 2; add local; return $local}
                use
                """));
    }

    private static void resolvesNamespacesAndEnsembles() {
        var tcl = Tcl.of();
        Check.equal("a proc runs in its defining namespace", 2L, tcl.eval("""
                namespace eval app {
                    variable count 0
                    proc bump {} {variable count; incr count}
                    namespace ensemble create
                }
                app bump
                app::bump
                """));
        Check.equal("namespace current returns a qualified name", "::app", tcl.eval("namespace eval app {namespace current}"));
        Check.equal("namespace and info are ensembles", 1L, tcl.eval("namespace ensemble exists namespace"));
        Check.equal("info reports a procedure", "proc", tcl.eval("info cmdtype app::bump"));
        Check.equal("namespace which returns the command name", "::app::bump", tcl.eval("namespace which app::bump"));
    }

    private static void carriesErrorsAndReturnOptions() {
        var tcl = Tcl.of();
        Check.equal("catch stores an error result", "boom", tcl.eval("catch {error boom} result options; set result"));
        Check.equal("catch returns the error code", 1L, tcl.eval("catch {throw {APP BAD} boom}"));
        Check.equal("try trap matches an error-code prefix", "handled",
                tcl.eval("try {throw {APP BAD} boom} trap {APP} {msg opts} {set x handled}"));
        Check.equal("finally runs after success", "1",
                tcl.eval("set done 0; try {set x ok} finally {set done 1}; set done"));
        try {
            tcl.eval("missing-command", "app.tcl");
            Check.that("an error leaves eval", false);
        } catch (TclException.Error error) {
            Check.equal("an unknown command has a lookup code", List.of("TCL", "LOOKUP", "COMMAND", "missing-command"), error.errorCode());
            Check.that("an error trace names its origin", error.errorInfo().contains("app.tcl"));
        }
    }

    private static void callsJavaAndBuildsCallbacks() {
        var tcl = Tcl.of();
        var host = new Host();
        tcl.set("host", host);
        Check.equal("a script calls a public Java method", "hello Ada", tcl.eval("$host greet Ada"));
        Check.equal("Java void becomes an empty result", "", tcl.eval("$host clear"));
        Check.that("Tcl.current is bound during a Java call", tcl.eval("$host interpreter") == tcl);

        tcl.set("values", List.of(-1, 2, 3).stream());
        Check.equal("a command name converts to a stream predicate", List.of(2, 3), tcl.eval("""
                proc positive {x} {expr {$x > 0}}
                set filtered [$values filter positive]
                $filtered toList
                """));
    }

    private static void createsJavaObjects() {
        var tcl = Tcl.of().types(Json.class, ArrayList.class);
        Check.equal("a class command runs a constructor with new", new Json.Str("admin"), tcl.eval("Json.Str new admin"));
        Check.equal("text converts to a numeric constructor parameter", new Json.Num(3), tcl.eval("Json.Num new 3"));
        Check.equal("text converts to a boolean constructor parameter", Json.TRUE, tcl.eval("Json.Bool new true"));
        Check.equal("a class command runs a static method", Json.Object.of("name", "Ada"), tcl.eval("Json.Object of name Ada"));
        Check.equal("text prefers a String overload", new Json.Str("41"), tcl.eval("[[Json.Object of] with age 41] get age"));
        Check.equal("a number picks the numeric overload", new Json.Num(41), tcl.eval("[[Json.Object of] with age [expr 41]] get age"));
        Check.equal("a permitted Class value accepts new", new Json.Str("x"), tcl.eval("[[Json of y] getClass] new x"));
        Check.equal("a script builds a JSON document", "{\"name\":\"Ada\",\"age\":41,\"tags\":[\"admin\",\"ops\"]}", tcl.eval("""
                set tags [ArrayList new]
                $tags add [Json of admin]
                $tags add [Json of ops]
                set user [Json.Object of name Ada]
                set user [$user with age [expr 41]]
                set user [$user with tags [Json.Array of $tags]]
                $user text
                """));

        tcl.set("nothing", null);
        try {
            tcl.eval("Json.Array new $nothing");
            Check.that("a constructor exception leaves eval", false);
        } catch (TclException.Error error) {
            Check.equal("a constructor exception has a JAVA code", List.of("JAVA", "java.lang.NullPointerException"), error.errorCode());
        }
        try {
            tcl.eval("Json new");
            Check.that("an interface cannot be instantiated", false);
        } catch (TclException.Error error) {
            Check.equal("an interface names itself", "cannot instantiate \"json.Json\"", error.getMessage());
        }
        tcl.set("builder", StringBuilder.class);
        try {
            tcl.eval("$builder new");
            Check.that("new needs a permitted class", false);
        } catch (TclException.Error error) {
            Check.equal("a refused new has a class lookup code", List.of("TCL", "LOOKUP", "CLASS", "java.lang.StringBuilder"), error.errorCode());
        }
    }

    private static void importsClasses() {
        var tcl = Tcl.of();
        try {
            tcl.eval("import java.util.HashMap");
            Check.that("import needs a permitted pattern", false);
        } catch (TclException.Error error) {
            Check.equal("a refused import has a class lookup code", List.of("TCL", "LOOKUP", "CLASS", "java.util.HashMap"), error.errorCode());
        }
        tcl.imports("java.util.*", "json.*");
        Check.equal("import registers the simple name", List.of("HashMap"), tcl.eval("import java.util.HashMap"));
        Check.equal("import as renames", List.of("Items"), tcl.eval("import java.util.ArrayList as Items"));
        Check.that("an imported class creates objects", tcl.eval("Items new") instanceof ArrayList);
        Check.equal("import resolves a member class by its Java name", new Json.Str("hi"), tcl.eval("import json.Json.Str as S; S new hi"));
        Check.equal("import registers member classes", List.of("Map", "Map.Entry"), tcl.eval("namespace eval app {import java.util.Map}"));
        Check.equal("import registers in the current namespace", List.of("::app::Map"), tcl.eval("info commands ::app::Map"));
        Check.equal("import does not register in the root", List.of(), tcl.eval("info commands Map"));
        try {
            tcl.eval("import java.util.NoSuchClass");
            Check.that("an unknown class cannot be imported", false);
        } catch (TclException.Error error) {
            Check.equal("an unknown class has a class lookup code", List.of("TCL", "LOOKUP", "CLASS", "java.util.NoSuchClass"), error.errorCode());
        }
    }

    private static void testsTypes() {
        var tcl = Tcl.of().types(Json.class);
        tcl.set("nothing", null);
        Check.equal("instanceof is true for the class of the value", true, tcl.eval("instanceof [Json of x] Json.Str"));
        Check.equal("instanceof is false for another class", false, tcl.eval("instanceof [Json of x] Json.Num"));
        Check.equal("instanceof accepts a Class value", true, tcl.eval("instanceof [Json of x] [[Json of y] getClass]"));
        Check.equal("instanceof of null is false", false, tcl.eval("instanceof $nothing Json.Str"));
        Check.equal("instanceof works in if", "yes", tcl.eval("if {[instanceof [Json of x] Json]} {set r yes} else {set r no}"));
        tcl.eval("""
                proc describe {v} {
                    switch -instanceof $v {
                        Json.Num { return "number [$v value]" }
                        Json.Str { return "text [$v value]" }
                        default { return other }
                    }
                }
                """);
        Check.equal("switch -instanceof selects by class", "text x", tcl.eval("describe [Json of x]"));
        Check.equal("switch -instanceof tries patterns in order", "number 2.0", tcl.eval("describe [Json.Num new 2]"));
        Check.equal("switch -instanceof falls back to default", "other", tcl.eval("describe hello"));
        try {
            tcl.eval("instanceof 1 NoSuchClass");
            Check.that("instanceof needs a class command", false);
        } catch (TclException.Error error) {
            Check.equal("an unknown class command has a class lookup code", List.of("TCL", "LOOKUP", "CLASS", "NoSuchClass"), error.errorCode());
        }
    }

    private static void streamsSourceCommands() throws IOException {
        var tcl = Tcl.of();
        var reader = new CommandGuardReader("set x 1\nincr x\n", 8, tcl);
        Check.equal("reader evaluation returns the last command", 2L, tcl.eval(reader));
        Check.that("the first command ran before the second was read", reader.firstCommandRan());
    }

    private static void rejectsConcurrentUse() throws Exception {
        var tcl = Tcl.of();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        tcl.set("block", new Block(entered, release));
        var thread = Thread.ofVirtual().start(() -> tcl.eval("$block waitHere"));
        entered.await();
        try {
            tcl.eval("set x 1");
            Check.that("another thread cannot enter a running interpreter", false);
        } catch (TclException.Error error) {
            Check.equal("busy use has the TCL BUSY code", List.of("TCL", "BUSY"), error.errorCode());
        } finally {
            release.countDown();
            thread.join();
        }
    }

    public static final class Host {

        public String greet(String name) {
            return "hello " + name;
        }

        public void clear() {}

        public Tcl interpreter() {
            return Tcl.current();
        }
    }

    public record Block(CountDownLatch entered, CountDownLatch release) {

        public void waitHere() throws InterruptedException {
            entered.countDown();
            release.await();
        }
    }

    private static final class CommandGuardReader extends Reader {

        private final String source;
        private final int boundary;
        private final Tcl tcl;
        private int at;
        private boolean firstCommandRan;

        private CommandGuardReader(String source, int boundary, Tcl tcl) {
            this.source = source;
            this.boundary = boundary;
            this.tcl = tcl;
        }

        @Override
        public int read(char[] target, int offset, int length) {
            if (at == source.length()) return -1;
            if (at == boundary) {
                firstCommandRan = tcl.exists("x");
                if (!firstCommandRan) throw new AssertionError("the next command was read before the first command ran");
            }
            target[offset] = source.charAt(at++);
            return 1;
        }

        @Override
        public void close() {}

        private boolean firstCommandRan() {
            return firstCommandRan;
        }
    }
}
