# Runtime reference

Command syntax uses `? ?` for optional words. An ellipsis (`…`) means that
the command accepts more words of the same kind.

This document is the contract. An implementation must match these rules. A
script author uses this document to find a fact.

Terms keep one meaning. See [Glossary](#glossary).

## Glossary

| Term | Meaning |
|---|---|
| interpreter | The `Tcl` object. It holds the namespace tree and the frame stack. |
| host | The Java program that creates the interpreter. |
| script | Source text. A sequence of commands. |
| command | A named operation: a builtin, a proc, or a function the host registered. |
| command list | A `List<Object>` of words after substitution. The host builds one in Java. |
| word | One argument after substitution. |
| value | A JVM object in a variable or as a result. |
| namespace | A node with commands, variables, and child namespaces. |
| frame | One level of the call stack. |
| proc | A command whose body is a script. |
| substitution | Replace `$name` and `[script]` in a word. |
| method | A public Java method. |
| resolve | Find a command or a variable by name. |
| slot | What a variable table stores: a value, an array, or a link. |
| origin | A name the host gives an eval, often a path. `info script` returns it. |
| options | The return-options dictionary of one evaluation. A `Map`. |
| busy | Another thread called `eval` or `call` while this interpreter was running. |

## Public types

The package exposes these types. All other types are package-private.

### `Repl`

| Member | Action |
|---|---|
| `Repl.of(Evaluator)` | Create a REPL with Tcl completeness rules and no prompts. |
| `Repl.of(Evaluator, Completer)` | Create a REPL with a caller-supplied completeness rule and no prompts. |
| `complete(source)` | Return whether `source` has no unclosed brace, quote, or command substitution. |
| `prompts(primary, continuation)` | Return a copy that writes prompts before input lines. |
| `interactive()` | Return a copy with `% ` and `> ` prompts. |
| `noPrompts()` | Return a copy with no prompts. |
| `results(ResultWriter)` | Return a copy with a custom result writer. |
| `failures(ErrorWriter)` | Return a copy with a custom error writer. |
| `run(reader, out)` | Read to EOF. Write results and errors to `out`. |
| `run(reader, out, errors)` | Read to EOF. Write results and errors separately. |

`Evaluator` receives a `Reader` for one complete command. It does not receive
the REPL's input reader. A normal binding is `Repl.of(tcl::eval)`.

The REPL reads one line at a time. It retains only the current incomplete
command. It adds each line terminator to that command before it calls the
completer. It does not call the evaluator for a blank line.

The default result writer writes non-null, non-empty results with a trailing
newline. The default error writer writes `error: `, the exception message, and
a trailing newline. An evaluator exception does not stop the REPL.

The default has no prompts. `interactive()` enables `% ` for a new command and
`> ` for a continuation. Prompts, results, and errors flush immediately.

At EOF, an incomplete command becomes one failure and does not reach the
evaluator. `Result.evaluated()` counts evaluator calls. `Result.failures()`
counts evaluator failures and this EOF failure. `Result.successful()` is true
only when there are no failures and no incomplete command.

The caller closes the input and output streams. The REPL does not close them.

### `Tcl`

| Member | Action |
|---|---|
| `Tcl.of()` | Create an interpreter. Install builtins in `::`. Current namespace is `::`. |
| `set(name, value)` | Store `value` under `name`. Resolve `name` from `::`. Create intermediate namespaces. Return this interpreter. |
| `get(name)` | Return the value. Resolve `name` from `::`. Throw `TclException` if the name is not set. |
| `exists(name)` | Return whether the name is set. `null` is set. Unset is not. |
| `command(name, Command)` | Register a command. Resolve `name` from `::`. Create intermediate namespaces. A second register under the same name replaces the first. |
| `command(name, Object)` | Register a host object as a command. The first argument is the method name. The rest are method arguments. |
| `eval(script)` | Parse and run `script` in the current frame. Return the result of the last command. An empty script returns `""`. |
| `eval(script, origin)` | Same as `eval(script)`. `origin` is the name `info script` and `info frame` report. |
| `eval(Reader)` | Parse one command at a time from the reader. Run each command. Return the result of the last command. Throw `IOException` if the reader fails. |
| `eval(Reader, firstLine)` | Same as `eval(Reader)`. `firstLine` is the line number of the first character. Default is 1. |
| `eval(Reader, firstLine, origin)` | Same as `eval(Reader, firstLine)`. `origin` is the source name. |
| `eval(Script)` | Run a parsed or constructed script in the current frame. |
| `eval(List<? extends List<?>> commands)` | Run each command list in order. No parse. No substitution. |
| `invoke(argv…)` | Run one command list. Words are already values. No substitution. |
| `invoke(List<?> argv)` | Same as `invoke(argv…)`. |
| `call(name, args…)` | `invoke` with `name` as the first word. `name` resolves from `::`. |
| `predicate(command)` | A `Predicate<Object>` that `call`s `command`. See [Adapters](#adapters). |
| `function(command)` | A `Function<Object, Object>` that `call`s `command`. |
| `consumer(command)` | A `Consumer<Object>` that `call`s `command`. |
| `runnable(command, args…)` | A `Runnable` that `call`s `command` with `args`. |
| `callable(command, args…)` | A `Callable<Object>` that `call`s `command` with `args`. |
| `comparator(command)` | A `Comparator<Object>` that `call`s `command` with two arguments. |
| `Tcl.current()` | Return the interpreter bound to this thread’s running `eval` or `call`. Error if none. |

`eval(String)` is `eval` of a `StringReader`. The reader form is the primitive
for source text. `invoke` and `eval(List)` are the primitives for the host.
Do not build a script string in Java.

`origin` is a name the host chooses, often a path. Empty origin means the script has no file. Nested `if` / `proc` bodies inherit the origin of the script that contained the braces.

`eval(Reader)` does not close the reader. The host closes it.

`set`, `get`, `exists`, `command`, `eval`, `invoke`, `call`, and the adapters
take a reentrant lock. See [Threads](#threads).

`get` and `exists` distinguish unset from `null`. A variable that holds `null`
exists.

## Java API

The host drives the interpreter with objects, not with concatenated source.

### Command lists

A command list is a `List<Object>`. Each element is one word **after**
substitution. `invoke` dispatches it the same way `eval` dispatches a
command that has already been substituted.

```
tcl.invoke("set", "x", user);
tcl.invoke(counter, "inc");
tcl.invoke(List.of("incr", "x", 2L));
```

The first form is `call` when the first word is a command name. The second
form is a method call. There is no `$` and no quoting.

A script list is a list of command lists. `eval` runs them in order. The
result is the result of the last command. An empty script list returns `""`.
Interrupt is checked between commands.

```
tcl.eval(List.of(
    List.of("set", "i", 0L),
    List.of("incr", "i"),
    List.of(counter, "inc")
));
```

`{*}` does not apply. The host already has a `List`. If one word should
expand, the host adds those elements when it builds the list.

`eval(Script)` runs an AST. `Script.parse` builds one from text.
`Script.lists(commands)` builds one from command lists. Each object becomes
a `Part.Value`. See [Script](#script).

### Script arguments

Commands that take a body (`if`, `while`, `for`, `foreach`, `proc`,
`uplevel`, `catch`, `try`, `namespace eval`) call `Values.script`.

| Input | Action |
|---|---|
| `String` | parse, then eval |
| `Script` | eval the AST |
| `List` whose every element is a `List` | `eval` of that script list |
| other | error |

Do not stringify a `List` into source. A host object in a body would not
survive quoting.

`if` still takes a test as an expression string **or** as a value
`Values.bool` already accepts. From Java, pass a `Boolean` or a `Number`.

```
tcl.invoke("if", user.active(), List.of(List.of(user, "start")));
```

`proc` args already use `Values.list`, so `List.of("a", "b")` is valid.

### Adapters

These wrap `call` for JDK functional interfaces. The command name is
resolved from `::` at each invocation, so a later `proc` is seen.

| Method | SAM | Call |
|---|---|---|
| `predicate(name)` | `Predicate<Object>` | `call(name, x)` → `Values.bool` |
| `function(name)` | `Function<Object, Object>` | `call(name, x)` |
| `consumer(name)` | `Consumer<Object>` | `call(name, x)` |
| `runnable(name, args…)` | `Runnable` | `call(name, args)` |
| `callable(name, args…)` | `Callable<Object>` | `call(name, args)` |
| `comparator(name)` | `Comparator<Object>` | `call(name, a, b)` → `Values.integer` |

```
items.stream().filter(tcl.predicate("positive")).toList();
```

Same lock as `eval`. Sequential streams on this thread work. `parallel()`
returns `TCL BUSY`. `runnable` / `callable` are for an idle interpreter on
a virtual thread. Do not `submit` them from inside `eval`.

### `Command`

```
Object call(Tcl tcl, List<Object> args)
```

`args` is the words after the command name. Return a value. Throw
`TclException` to fail.

### `TclException`

Every command evaluation produces a result and a return-options dictionary.
That is the Tcl 9 model. See [Results and errors](#results-and-errors).

Sealed. The subtype is the return code. Host code catches `TclException.Error`.

| Type | Code | Who throws it | Who intercepts it |
|---|---|---|---|
| (success) | `0` ok | a command that returns a value | nobody. The result is the value. |
| `Error` | `1` error | `error`, `throw`, a builtin, a method | `catch`, `try`, then the caller of `eval` / `call` |
| `Return` | `2` return | `return` | `catch`, `try`, then `proc` (decrements `-level`), then the current `eval` |
| `Break` | `3` break | `break` | `catch`, `try`, then `while`, `for`, and `foreach`. Else error. |
| `Continue` | `4` continue | `continue` | `catch`, `try`, then `while`, `for`, and `foreach`. Else error. |

Other integer codes are allowed. They travel in the options dictionary. There is
no extra Java subtype for them.

`Error` methods:

| Member | Value |
|---|---|
| `getMessage()` | the result, the human message |
| `options()` | the return-options `Map` |
| `errorInfo()` | `-errorinfo`, the human traceback |
| `errorCode()` | `-errorcode`, a list |
| `errorLine()` | `-errorline` |
| `errorStack()` | `-errorstack` |
| `getCause()` | the Java throwable when `-errorcode` starts with `JAVA`, else null |

### `Values`

| Method | Result |
|---|---|
| `string(value)` | A string, for interpolation and for Java `String` parameters. |
| `bool(value)` | A boolean, for `if` / `while` / `for` after `expr`. |
| `integer(value)` | A `long`, for `incr` and integer `expr`. |
| `number(value)` | A `double`, for general `expr`. |
| `list(value)` | A `List<Object>`, for `proc` arguments and `{*}`. |
| `dict(value)` | A `Map<Object, Object>`. No `dict` command in v1. The mapping is defined now. |
| `script(value)` | A script to eval. See [Script arguments](#script-arguments). |

### `string`

| Input | Output |
|---|---|
| `null` | `""` |
| `String` | itself |
| integer `Number` | decimal digits, no fraction |
| other `Number` | canonical decimal form. `1.0` is `1.0`. |
| `Boolean` | `"1"` or `"0"` |
| `List` | Tcl list form. Space between elements. Braces when an element needs them. |
| `Map` | Tcl list form of key, value, key, value. Same brace rule. |
| other | `String.valueOf(value)` |

### `bool`

| Input | Output |
|---|---|
| `Boolean` | itself |
| `Number` | `false` if the number is zero, else `true` |
| `String` | `true` / `yes` / `on` / `1`, or `false` / `no` / `off` / `0`, any case. Or a number. |
| `null` | `false` |
| other | error |

A host object is not a boolean.

### `list`

| Input | Output |
|---|---|
| `List` | itself. Elements do not change. |
| `String` | parse as a Tcl list. No `$` and no `[]` in this parse. |
| `Stream` | error. Call `toList`. A stream is one-shot. |
| other | error |

### `dict`

| Input | Output |
|---|---|
| `Map` | itself |
| `List` of even length | pairs in a `LinkedHashMap`, insertion order |
| `String` | parse as a list, then pairs |
| other | error |

Odd length is an error.

### `script`

| Input | Action |
|---|---|
| `String` | parse, then that `Script` |
| `Script` | itself |
| `List` whose every element is a `List` | `Script.lists` of those command lists |
| other | error |

### `Script`

`Script.parse(source)` and `Script.parse(source, firstLine)` return the AST
for a complete string. `firstLine` is the line number of the first line of
`source`. Default is 1.

A command stores the line it started on. A braced word stores its body and
its line. `if` and `proc` parse that body with that line and the current
origin, so an error points at the file the body came from.

`Script.parse(source, firstLine, origin)` stores that origin on the script.

`Script.lists(commands)` builds a script from command lists. Line numbers
are 1, 2, 3, … in order. Origin is empty. `type` in `info frame` is `eval`.

The parser is hand-rolled. It does not use `peg`. It reads one command at a
time from a character source. `eval(Reader)` calls that parse in a loop. It
does not read the rest of the script before it runs the command.

A character source peeks one character and counts lines. Unclosed `{`, `"`,
or `[` at the end of the reader is an error.

`Script.Part`:

| Part | Source |
|---|---|
| `Text` | literal text. Escapes already resolved. |
| `Variable(name, index)` | `$name`, `${name}`, `$name(index)`. `index` is absent when there is no `(…)`. |
| `Substitution(script)` | `[script]` |
| `Expanded(word)` | `{*}word` |
| `Value(object)` | a word the host already substituted. `invoke` uses only this. |

## Values from scripts

| Source | Type |
|---|---|
| bare, quoted, or braced word | `String` |
| `expr` arithmetic | `Long` if both operands are integers, else `Double` |
| `incr` | `Long` |
| Java `void` | `""` |
| Java `null` | `null` |
| `List` or `Map` from Java | itself |
| other host object | itself |

The interpreter does not store a second form of a value. A command that needs
a number calls `Values`. A command that does not need a string does not
stringify.

## Substitution

Walk the parts of a word.

If the word is braced, the result is the body as a `String`. No substitution.

If the word has one part, the result is that part as an object:

- `Text` → the text
- `Variable` → the value in the variable
- `Substitution` → the result of that script
- `Value` → the object

If the word has more than one part, stringify each part and concatenate.

`{*}word` substitutes `word`, then expands:

- a `List` → one word per element. Elements stay objects.
- a `String` → `Values.list`, then one word per element.
- other → error

An empty command does not change the result of the script.

## Syntax

The dodekalogue.

1. A script is commands. Newline or `;` ends a command.
2. A command is words. Blanks separate words.
3. `"quotes"` substitute.
4. `{braces}` do not substitute. Braces nest. `\}` does not close.
5. `$name`, `${name}`, `$name(index)` substitute a variable. The index
   substitutes.
6. `[script]` substitutes the result of that script.
7. `\` escapes. See [Escapes](#escapes).
8. `#` starts a comment only where a command may start.
9. `{*}` expands one word into zero or more words.

`]` ends a word only inside `[…]`. At the top level, `foo]bar` is one word.

A word that starts with `{` or `"` is a braced or quoted word, or it is an
error. It is never a bare word.

### Variable names in `$`

```
$ { any characters except } }
$ ::* name (:: name)* ( index )?
name = letter, digit, or _
```

`$::foo::bar` is one variable. `$foo::bar` is one variable.
`$foo::bar.baz` is variable `foo::bar` then text `.baz`.

### Escapes

In quotes and in bare words:

| Sequence | Result |
|---|---|
| `\n` `\t` `\r` `\a` `\b` `\f` `\v` | the usual control character |
| `\xHH` | one byte, hex |
| `\uHHHH` | one Unicode code unit, hex |
| `\ooo` | octal, up to three digits |
| `\` newline blanks | one space |
| `\` other | that other character |

Inside braces, only nesting and backslash-newline apply. `\n` inside braces
is the two characters `\` and `n`.

## Namespaces

The interpreter holds one tree. The root is `::`. The root has parent `null`.
The tail of the root is `""`. The display name of the root is `::`.

Each namespace has three maps: children, commands, variables. The same tail
may exist in all three.

Each namespace also holds:

- a command path, a list of namespaces. Default is empty.
- an unknown handler, a script. Default is empty.
- an export list, a list of glob patterns. Default is empty.

A command entry is `CommandRef(command, origin, originalName)`. A proc stores
its home namespace as `origin`. An ensemble is a command. See
[`namespace ensemble`](reference-namespace.md#namespace-ensemble-create).

You cannot delete `::`. You cannot delete a namespace that a frame on the
stack uses, or a child of such a namespace.

### Name strings

`::` separates. One or more colons in a row is one separator. A leading `::`
is absolute.

| Written | Absolute | Parts |
|---|---|---|
| `foo` | no | `[foo]` |
| `foo::bar` | no | `[foo, bar]` |
| `::foo::bar` | yes | `[foo, bar]` |
| `::` | yes | `[]` |

### Command resolution

1. Unqualified: find in the current namespace.
2. If missing, find in each namespace on the path of the current namespace,
   in order.
3. If missing, find in `::`. Skip this find when that namespace was already
   searched.
4. Qualified relative: walk children from the current namespace. Do not use
   the path. Do not find in `::` after a miss.
5. Qualified absolute: walk from `::`.
6. Miss: run the unknown handler of the current namespace. If that handler is
   empty, error `invalid command name "…"`.

### Variable resolution

1. In a proc frame, an unqualified name is a local. It does not find a
   namespace variable.
2. In a non-proc frame, an unqualified name is a variable of that namespace.
3. A name that contains `::` is a namespace variable. It is never a local.
4. `set` on a qualified name does not create namespaces. Missing parent is
   an error.
5. `$a(i)` is an array element in the table the name resolved to.

A scalar and an array cannot share a name. That is an error.

### Frames

| Cause | `ns` | `vars` | proc frame |
|---|---|---|---|
| rest, level `#0` | `::` | `::` variables | no |
| `namespace eval foo` | `foo` | `foo` variables, same map | no |
| call of a proc defined in `foo` | `foo` (the origin, not the caller) | new local map | yes |

`uplevel` evals in the target frame. It uses that frame’s namespace and
variables. `uplevel #0` is `::`.

Level:

- default `1`
- a number: that many frames up
- `#n`: absolute index. `#0` is the root frame.

### `upvar`

- Unqualified `other`: a name in the target frame.
- Qualified `other`: a namespace variable. The level does not apply to that
  pair.

A cycle of links is an error.

Java `set`, `get`, `exists`, `command`, and `call` resolve from `::`. They
create intermediate namespaces.

A script `set` does not create namespaces. `proc foo::bar {} {}` does create
intermediate namespaces. `namespace eval` does create them.

## Dispatch

After substitution:

1. Empty argv → `""`.
2. First word is a `String` that resolves to a command → call it with the
   rest.
3. First word is any other object → Java method. Second word is the method
   name. The rest are arguments. Missing method name is an error.
4. Else → `invalid command name`.

Commands and variables are separate. `set set 1` is valid.

## Java methods

Public methods only. No `setAccessible`. No fields. No constructors.

- First word is a `Class` → static method on that class.
- First word is any other object → instance method on `getClass()`.
- Inherited public methods are included.
- Java `void` returns `""`.
- Java `null` returns `null`.
- A thrown exception becomes `TclException.Error` with that cause.

Overload: same arity, then one best conversion. Zero matches or two equal
matches is an error.

### Argument conversion

| Value | Parameter |
|---|---|
| exact type or boxed pair | as is |
| `Number` | numeric primitive or box, usual widening |
| any | `String` or `CharSequence` via `Values.string` |
| `String` | enum constant, if the name matches |
| remaining values | Java varargs array |
| `null` | any reference. Error on a primitive. |
| command name or `Command` | a functional interface with one abstract method. See [SAM](#sam). |

A Java exception becomes `-errorcode` `{JAVA className}` and `getCause()` on
`TclException.Error`. The Java stack is the cause. It is not copied into
`-errorinfo`.

### SAM

If a Java parameter is a functional interface with one abstract method, and
the Tcl argument is a command name or a `Command`, the interpreter wraps
that command.

| Java method | Tcl call | Result conversion |
|---|---|---|
| `Predicate.test(x)` | `cmd x` | `Values.bool` |
| `Function.apply(x)` | `cmd x` | as is |
| `Consumer.accept(x)` | `cmd x` | discarded |
| `Runnable.run()` | `cmd` | discarded |
| `Comparator.compare(a, b)` | `cmd a b` | `Values.integer` |

The wrapper takes the interpreter lock. A call from another thread is
`TCL BUSY`. Sequential `Stream.filter` on this thread works.
`stream.parallel()` with a Tcl predicate fails. `$executor submit worker`
with a Tcl `worker` fails.

```
proc positive {x} { expr {$x > 0} }
$stream filter positive
```

## Threads

`set`, `get`, `exists`, `command`, `eval`, `invoke`, `call`, and the adapters
take a reentrant lock.

| Caller | Interpreter | Result |
|---|---|---|
| same thread, nested | running | allowed |
| other thread | idle | allowed |
| other thread | running | error `TCL BUSY`. Do not wait. |

Waiting deadlocks. Nested `if`, proc, and SAM callbacks on the same thread
are required.

The package does not fork virtual threads. It does not wrap
`StructuredTaskScope`. The host uses
`Executors.newVirtualThreadPerTaskExecutor()` in try-with-resources, as
other tuul packages do.

Two virtual threads that must script at the same time use two interpreters.

During `eval` and `call`, a `ScopedValue` holds the interpreter.
`Tcl.current()` returns it. Host methods invoked from a script use
`Tcl.current()`. Outside eval, `current()` errors.

Between commands, `eval` checks `Thread.interrupted()`. If set, it fails
with `-errorcode {TCL INTERRUPTED}`. A Java method that is already running
is not aborted.

The interpreter is not a `Flow.Subscriber`. `onNext` arrives on the
publisher’s thread. The host subscribes and may call `tcl.call` if the lock
is free. A script may hold a `Publisher` and call synchronous methods. It
does not `subscribe` with a Tcl proc.

### JDK types

| Type | Rule |
|---|---|
| `Stream` | a host object. Sequential callbacks only. `Values.list` does not drain it. Close it in `finally`. |
| `Gatherer` | a host object. Scripts do not author gatherers. |
| `Iterable` / `Iterator` / array | `foreach`, or method calls. |
| `Optional` | stays `Optional`. Not unwrapped. Not a boolean. `info exists` is true if the variable holds it. |
| `AutoCloseable` | `$x close` in `finally`. `eval(Reader)` does not close the reader. |
| `Path` | a value. No `file` command. Do not pass `Files.class` unless the script may read files. |
| `Appendable` / `Writer` | `$out write $text`. That is how a script prints. |
| `CompletionStage` / `Future` | `$f get` blocks. The host should join. |
| `java.time`, `Pattern`, `Matcher`, `SequencedCollection` | objects and methods. |

## Results and errors

This section follows Tcl 9: [return](https://www.tcl-lang.org/man/tcl9.0/TclCmd/return.html),
[catch](https://www.tcl-lang.org/man/tcl9.0/TclCmd/catch.html),
[error](https://www.tcl-lang.org/man/tcl9.0/TclCmd/error.html),
[throw](https://www.tcl-lang.org/man/tcl9.0/TclCmd/throw.html),
[try](https://www.tcl-lang.org/man/tcl9.0/TclCmd/try.html),
[info](https://www.tcl-lang.org/man/tcl9.0/TclCmd/info.html),
[tclvars](https://www.tcl-lang.org/man/tcl9.0/TclCmd/tclvars.html).

Each command evaluation produces:

1. a result (a JVM object)
2. a return code (`0`–`4`, or another integer)
3. a return-options dictionary (`Map`)

A code of `0` continues to the next command. Any other code stops the script.
The exception type is that code. `catch` and `try` turn it back into a value.

### Return-options dictionary

Always present:

| Key | Value |
|---|---|
| `-code` | the return code, a `Long` |
| `-level` | how many proc frames still have to unwind. `0` except during `TCL_RETURN`. |

When the code is `1` (error), also present:

| Key | Value |
|---|---|
| `-errorinfo` | human traceback, a string |
| `-errorcode` | machine list. Default `{NONE}`. |
| `-errorline` | line of the command that failed, in its script |
| `-errorstack` | even list of token, parameter pairs |

`return` may set any other key. `catch` stores the whole dictionary. Scripts
rethrow with `return -options $options $result`.

When `try` or a handler itself fails, the previous dictionary is stored under
`-during` in the new dictionary.

### `-errorinfo` (human)

Built as the error unwinds. Same shape as Tcl 9 `::errorInfo`:

```
can't read "x": no such variable
    while executing
"set y $x"
    (procedure "foo" line 2)
    invoked from within
"foo"
    (file "app.tcl" line 16)
```

The first line is the message. Each frame appends `while executing` or
`invoked from within`, the command as written (no substitution), and a
location.

| Location | When |
|---|---|
| `(procedure "name" line N)` | inside a proc. `N` is the line in the proc body. |
| `(file "origin" line N)` | the eval has an origin. `N` is the line in that source. |

Only errors add to this trace. `return`, `break`, and `continue` do not.

If `error` is given an `info` argument, that string is the initial trace.
The command that contains `error` is not added.

### `-errorstack` (machine)

Even-sized list. Tokens from Tcl 9:

| Token | Parameter |
|---|---|
| `INNER` | the command that failed, after substitution, as a list of words |
| `CALL` | `info level 0` at that proc: name and arguments after substitution |
| `UP` | relative level of an `uplevel`, applies to the previous `CALL` |

`INNER` is first. Then `CALL` / `UP` as the stack unwinds.

`info errorstack` returns this list for the last error.

### `::errorInfo` and `::errorCode`

Ordinary variables in `::`. After an error they hold `-errorinfo` and
`-errorcode`. `catch` still sets them. Then it returns.

### `-errorcode` conventions

| Prefix | Meaning |
|---|---|
| `NONE` | no extra data |
| `ARITH DIVZERO msg` | division by zero in `expr` |
| `ARITH DOMAIN msg` | argument outside a function domain |
| `TCL LOOKUP COMMAND name` | unknown command |
| `TCL LOOKUP VARNAME name` | unset variable |
| `TCL WRONGARGS` | wrong number of arguments |
| `TCL BUSY` | another thread called this interpreter while it was running |
| `TCL INTERRUPTED` | `Thread.interrupted()` was set between commands |
| `JAVA className` | a Java method threw. `className` is the fully qualified class. |

`throw type message` sets `-errorcode` to `type`. Words go from general to
specific.

## Glob

`namespace export`, `namespace children`, `namespace import`, and
`switch -glob` use the same glob.

| Token | Matches |
|---|---|
| `*` | any sequence, including empty |
| `?` | one character |
| `[abc]` | one character in the set |
| `[a-z]` | one character in the range |
| `\` | the next character, as itself |
| other | that character |

## Out of scope for v1

No `puts`, `gets`, `read`, `open`, `close`, `source`, `file`, `glob` as a
command, `exec`, `socket`, `pwd`, `cd`, `exit`, `clock`, `after`.

No `list`, `lindex`, `llength`, `concat`, `append`, `dict`, `lappend`,
`split`, `join`.

No `eval` as a command, `subst`, `rename`, `unknown` as a command, `apply`,
`coroutine`, `trace`.

No `info hostname`, `info library`, `info loaded`, `info nameofexecutable`,
`info patchlevel`, `info tclversion`, `info class`, `info object`.

No `thread` command. No Tcl `Flow` subscriber. No parallel stream with a Tcl
callback. No event loop.

No sandbox. No bytecode.
