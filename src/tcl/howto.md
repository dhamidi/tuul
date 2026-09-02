# How-to

Each section is one task. Do the steps in order. One instruction per step.

Facts about commands, values, and names are in [reference.md](reference.md).

## Add a scripting layer to an application

1. Create one interpreter for the application: `var tcl = Tcl.of()`.
2. Pass the application object: `tcl.set("app", application)`.
3. Eval a script when you need the script to run: `tcl.eval(script)`.
4. To eval from a stream, pass a `Reader`: `tcl.eval(reader)`.
5. When the script came from a file, pass the origin:
   `tcl.eval(reader, 1, path.toString())`.
6. Read a variable the script wrote: `tcl.get("name")`.
7. Do not call one interpreter from two threads at the same time.

Nested `eval` on the same thread is allowed. A second thread while the
first is in `eval` gets `TCL BUSY`. Two virtual threads that must script at
once use two interpreters. The objects you pass keep their own rules for
threads.

There is no `source` and no `puts`. Open the reader in the host. Write output
in the host. The interpreter reads commands from the reader. It does not
open a file.

## Run an evaluator as a REPL

1. Bind the evaluator: `var repl = Repl.of(tcl::eval)`. The default has no
   prompts, so it also works with a pipe.
2. Enable terminal prompts when needed:
   `repl = repl.interactive()`.
3. Run it with caller-owned streams:
   `var report = repl.run(input, output, errors)`.
4. Close `input`, `output`, and `errors` in the host.

The REPL reads one line at a time. It uses Tcl completeness rules to keep a
multiline command together. It evaluates a complete command through the
interpreter's `Reader` API, writes its result, and continues after an error.
At end of input, an unfinished command is reported as a failure and is not
evaluated.

## Call a method on a host object

1. Pass the object: `tcl.set("obj", object)`.
2. In the script, write `$obj methodName arg1 arg2`.

The first word after substitution is the object. The second word is the
method name. The rest are arguments.

The interpreter calls a public method only. It does not read fields. To
create an object in a script, see
[Create a Java object from a script](#create-a-java-object-from-a-script).

If the first word is a `Class`, the interpreter calls a static method.

## Register a command that is a host object

1. Call `tcl.command("app", application)`.
2. In the script, write `app methodName arg1 arg2`.

This registers `::app` as a command. The first argument is the method name.

You may also create a namespace named `app`. A command and a namespace may
share a tail. `app start` uses the command. `app::start` uses the namespace.

## Register a command written in Java

1. Call `tcl.command("greet", (interp, args) -> ...)`.
2. Return a JVM object. That object is the result of the command.
3. Throw `TclException` when the arguments are wrong.

The interpreter passes the current interpreter and the arguments after the
command name. It does not pass the command name.

## Create a Java object from a script

1. Permit the class in the host: `tcl.types(Json.class, ArrayList.class)`.
   Or open a pattern when the script names the class:
   `tcl.imports("json.*", "java.util.*")`.
2. In the script, import a class the host opened by pattern:
   `import java.util.ArrayList`. A class passed to `types` needs no
   `import`.
3. Write `ArrayList new` to run a constructor. Add arguments after `new`.
4. Write `Json of admin` to run a static method.

`import` registers the simple name and each public member class as
`Outer.Inner`. `import json.Json` gives `Json.Str`, `Json.Object`, and the
other members. Write `import java.util.HashMap as Dict` to choose the name.

A text word stays text. `Json.Num new 3` works, because `Num` has no
`String` constructor. `$o with age 41` picks `with(String, String)`. Write
`[expr 41]` when the numeric overload must win.

## Build a JSON value from a script

1. Pass the classes and a writer:

```java
var tcl = Tcl.of().types(Json.class, ArrayList.class);
tcl.set("out", writer);
```

2. Build the document in the script:

```tcl
set tags [ArrayList new]
$tags add [Json of admin]
$tags add [Json of ops]

set user [Json.Object of name Ada]
set user [$user with age [expr 41]]
set user [$user with tags [Json.Array of $tags]]
$user write $out
```

`Json.Object` is immutable, so each `with` returns a new object. `write`
streams the document to the writer.

## Test the type of a value

1. Write `instanceof $v Json.Str`. The result is a `Boolean`.
2. For one body per class, write `switch -instanceof`:

```tcl
switch -instanceof $v {
    Json.Num { expr {[$v value] + 1} }
    Json.Str { $v value }
    default  { error "unexpected value" }
}
```

3. A `Class` from `$v getClass` also works as the class argument.

## Put an object in a namespace

1. Use a qualified name from Java: `tcl.set("::app::db", database)`.
2. The call creates namespace `::app` if it does not exist.
3. In a script at the top level, write `$::app::db` or
   `namespace eval app { $db ... }`.
4. In a proc, write `variable db` before `$db`. An unqualified name in a proc
   is a local variable.

`set ::missing::x 1` in a script does not create `::missing`. That is an
error. `proc missing::f {} {}` does create `::missing`.

## Write a proc that uses a namespace variable

1. Create the namespace and the proc:

```
namespace eval app {
    variable count 0
    proc bump {} {
        variable count
        incr count
        return $count
    }
}
```

2. Call `app::bump` from any namespace.
3. `namespace current` inside `bump` is `::app`. The caller does not change
   that.

`variable count` links local `count` to `::app::count`. Without that line,
`incr count` creates a local and does not change `::app::count`.

## Use `global` for a root variable

1. Set the value at the top level, or from Java with `tcl.set("x", value)`.
2. In a proc, write `global x` before you read or write `x`.

`global x` is the same as `upvar #0 x x`. Both names refer to `::x`.

## Loop and stop early

1. Write `while {test} { body }` or `for {start} {test} {next} { body }`.
2. Write `foreach x $collection { body }` for an `Iterable`, array, `Stream`,
   or Tcl list.
3. Write `foreach {k v} $map { body }` for a `Map`.
4. Write `break` in the body to leave the loop.
5. Write `continue` in the body to go to the next element.

`return` leaves the current proc. The value of `return` is the result of the
proc. If the proc has no `return`, the result is the last command in the body.

## Call a command from Java

1. Register or define the command.
2. Call `tcl.call("bump", 10)`.

The name resolves from `::`. Arguments stay JVM objects. The host does not
stringify them.

## Drive the interpreter without a script string

Do not concatenate Tcl source in Java. Build command lists.

1. One command: `tcl.invoke("set", "x", user)`.
2. A method: `tcl.invoke(counter, "inc")`.
3. Several commands:

```
tcl.eval(List.of(
    List.of("set", "i", 0L),
    List.of("while", "$i < 3", List.of(
        List.of(counter, "inc"),
        List.of("incr", "i")
    ))
));
```

4. A body is a script list (every element is a `List`), a `Script`, or a
   string. A test for `if` / `while` may be a `Boolean` or a `Number`.

```
tcl.invoke("if", user.active(), List.of(List.of(user, "start")));
```

`while` still needs an expression string if the test must run again each
time. `"$i < 3"` is that string. The body is still a list.

## Filter a Java stream with a Tcl proc

1. Define the proc, as source or as lists.
2. Call `items.stream().filter(tcl.predicate("positive")).toList()`.

The predicate takes the lock. Use a sequential stream on this thread.

## Eval a body in another namespace

1. Write `namespace eval foo { ... }`.
2. Unqualified `set x 1` inside that body writes `::foo::x`.
3. Unqualified `set` as a command still finds `::set`, unless `foo` has its
   own command named `set`.

## Catch an error

1. Write `catch { script } err`.
2. A result of `0` means the script finished. `err` holds the result.
3. A result of `1` means an error. `err` holds the message.
4. Codes `2`, `3`, and `4` mean `return`, `break`, and `continue`.

Java methods that throw become code `1`. `-errorcode` starts with `JAVA`.

To rethrow and keep the traceback:

```
catch { script } result options
return -options $options $result
```

For `finally` or for a match on `errorcode`, write `try`:

```
try {
    $obj mightFail
} on error {msg} {
    error $msg
} finally {
    $obj close
}
```

## Switch on a value

1. Write `switch $kind a { ... } b { ... } default { ... }`.
2. For glob patterns, write `switch -glob $name *.txt { ... }`.
3. A body `-` uses the next body.

## Create an ensemble

1. Create commands in a namespace:

```
namespace eval app {
    proc start {} { ... }
    proc stop {} { ... }
    namespace ensemble create
}
```

2. Call `app start` or `app::start`. Both run `::app::start`.

## Set a command path

1. Write `namespace path {::helpers ::tcl}`.
2. Unqualified commands in the current namespace then find `::helpers` and
   `::tcl` before `::`.
3. Qualified names do not use the path.

## Read an error from Java

1. Eval inside `try`. Catch `TclException.Error`.
2. Read `e.getMessage()` for the human message.
3. Read `e.errorInfo()` for the traceback.
4. Read `e.errorCode()` for the machine list.
5. Read `e.getCause()` when a Java method threw.

```java
try {
    tcl.eval(reader, 1, "app.tcl");
} catch (TclException.Error e) {
    log(e.errorInfo());
    if (e.getCause() != null) e.getCause().printStackTrace();
}
```

## Inspect the interpreter

1. From a script, write `info exists x`, `info commands`, `info level`.
2. From Java, call the same command: `tcl.call("info", "commands")`.
3. Use `info frame` for line and origin of the current command.
4. Use `info errorstack` after an error that was not caught.
5. Use `info complete $line` before you eval a line from a `Reader`.

Do not add a second Java type that lists commands. Call `info`.

## Filter a stream from a script

1. Pass a sequential `Stream`: `tcl.set("xs", list.stream())`.
2. Define a proc: `proc positive {x} { expr {$x > 0} }`.
3. Call the Java method: `$xs filter positive`.
4. Collect: `set out [$xs toList]`.
5. Close in `finally` if you open a stream that needs it.

Do not call `$xs parallel` with a Tcl proc. That runs the proc on another
thread and returns `TCL BUSY`.

Do not `$executor submit myproc` with a Tcl proc. Same error.

## Script from two virtual threads

1. Create one interpreter per thread.
2. Fork virtual threads with
   `Executors.newVirtualThreadPerTaskExecutor()` in try-with-resources.
3. Each task evals on its own interpreter.
4. Do not share one interpreter across those tasks.

## Find the interpreter from a Java method

1. Call `Tcl.current()` inside a method that a script invoked.
2. Do not call it from a thread that is not in `eval`.

## Stop a script when the executor closes

1. Run `eval` on a virtual thread from that executor.
2. Close the executor. The thread is interrupted.
3. `eval` fails with `TCL INTERRUPTED` between commands.
