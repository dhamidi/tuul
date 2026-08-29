# Explanation

This document says why the design is this way. It is not a tutorial. It is not
a command list. For tasks, read [howto.md](howto.md). For rules, read
[reference.md](reference.md).

## What this package is

The host already has objects. The script must name them, call methods on them,
and call procs that the host or the script defined.

Tcl supplies the syntax: words, braces, `$`, `[]`, and `#`. The JVM supplies
the values. A variable holds a Java object. It does not hold a string with a
typed view.

The package has no I/O. A scripting layer that opens files or sockets is a
different package. The host reads scripts. The host writes output.

## Why values are JVM objects

Classic Tcl stores strings. A list is a string with a list view. A number is a
string with a number view.

That model fights the host. If `$app` becomes a string, the next command cannot
call a method on the application.

This interpreter does not convert a value to a string unless a command needs a
string. Lone `$x` and lone `[cmd]` keep the object. A quoted word that contains
`$x` builds a string.

Script text is still a string. The word `10` in `set i 10` is the string
`"10"`. `incr` and `expr` read a number from that string. After `incr`, the
variable holds a `Long`.

## Why braces do not build lists

`{a b c}` is quoting. The result is the string `a b c`.

If braces built a list, `if {true} { $c inc }` would break. The test and the
body must stay source. `if` and `proc` parse that source when they run.

`proc` and `{*}` parse a string as a Tcl list when they need a list. A Java
`List` is already a list. `Values.list` is that conversion.

## Why substitution has two results

One part in a word can be an object. Two parts in a word cannot.

`$c` is one part. The result is the counter.

`n=$c` is two parts. The interpreter stringifies each part and concatenates.

This rule is mechanical. It does not guess. It does not keep a hidden string
form next to the object.

## Why namespaces start at `::`

A flat command table is easy to write. It is hard to replace.

Command find, variable find, `proc` home, `uplevel`, and `$foo::bar` all need
the same tree. If those paths assume one table, a later namespace change
touches every path.

The tree exists from the first `set`. The root is `::`. There is no second
global table.

Unqualified names are not a special space. They are names in the current
namespace. For commands, a miss then finds the namespace path, then `::`. At
the top level the current namespace is `::`, so the first find and the last
find are one when the path is empty.

## Why a command and a namespace may share a tail

Tcl keeps commands and namespaces in different maps. `app start` finds a
command. `app::start` finds a command in a namespace.

The host uses this. `tcl.command("app", application)` registers a command.
`namespace eval app { proc start {} {} }` creates a namespace. The two do not
collide.

`namespace ensemble create` makes `app start` run a command in namespace
`app`. The three maps already allow a command and a namespace to share a
tail. The ensemble is a command in one map. The subcommands live in the
other.

## Why a proc remembers its namespace

A proc runs in the namespace that defined it. It does not run in the caller
namespace.

If the caller namespace followed the proc, `variable` would be ambiguous.
`namespace current` would change with the caller. `app::bump` would not be a
stable command.

The frame records this. A proc frame has `ns = origin` and a new local map. A
`namespace eval` frame has `ns = target` and the target variable map.

## Why `CommandRef` holds an origin

`namespace import` copies a name. The copy must still point at the source.
`namespace origin` reads that pointer.

If the command table stored only a function, import would need a new table
shape. `CommandRef` is that shape from the start.

## Why `return`, `break`, `continue`, and `error` exist

`proc`, `for`, and `while` are not complete without them.

A proc without `return` can only yield its last command. A loop without
`break` cannot stop early. A script without `error` cannot fail on purpose.

These four commands throw a sealed subtype of `TclException`. The subtype is
the Tcl 9 return code. `catch` and `try` intercept all four. Without `catch`
or `try`, `Error` leaves `eval`. `Break` and `Continue` outside a loop are
errors.

## Why a result and an options dictionary

Tcl 9 evaluates a command to a result, a code, and a dictionary. `catch` with
three arguments captures all three. `return -options $options $result`
replays them. That is how a script rethrows without losing the traceback.

The Java exception is that triple. `getMessage()` is the result.
`options()` is the dictionary. The host does not parse `errorInfo` to find
the code. The code is `-code`.

## Why two traces

`-errorinfo` is for a person. It uses the command as written. It names the
proc and the origin.

`-errorstack` is for a program. It uses substituted arguments. Tokens are
`INNER`, `CALL`, and `UP`, as in Tcl 9 `info errorstack`.

`::errorInfo` and `::errorCode` are copies of `-errorinfo` and `-errorcode`
after an error. They exist so a script that does not use three-argument
`catch` can still read the last error.

A Java throwable is `getCause()`. It is not appended to `-errorinfo`. Mixing
the two traces would make both worse.

## Why `catch` and `try` exist

A script that calls Java methods will hit exceptions. Without `catch`, every
failure leaves `eval`. The host would have to wrap every call.

`catch` is the small form. It returns a code and stores a result.
`try` is the form with `on`, `trap`, and `finally`. Java methods that throw
become `Error`. `trap` matches `errorcode` when the host or `error` set one.

## Why `switch` exists

`if` chains on string equality are long and easy to get wrong. `switch`
compares one value to a list of patterns. `-glob` uses the same glob as
`namespace export`. `-regexp` uses `java.util.regex.Pattern`. It does not
use Tcl ARE.

## Why the parser is hand-rolled

Tcl parse is one command at a time. A PEG retries alternatives. That costs
time on a long script.

Tcl syntax is also context-sensitive. `]` ends a word only inside `[…]`.
Backslash-newline eats the following blanks. `{*}` prefixes a word. `$name(i)`
parses an index that itself substitutes. A comment is valid only where a
command may start. PEG can encode some of this. It does not encode it well.

The parser reads one command from a character source and returns. It peeks
one character. It counts lines. It does not use `peg`.

## Why `eval` takes a `Reader`

The host may hold a large script. The interpreter must not require that
script as one string.

`eval(Reader)` parses one command, runs it, and reads the next command. It
does not buffer the rest of the reader. `eval(String)` is that loop over a
`StringReader`.

This is not Tcl I/O. The host opens the reader. The interpreter does not.

Pass an origin with the reader when the script came from a file. `info script`
returns that origin. `info frame` then has `type` `source` and a `file` key.
`-errorinfo` can print `(file "app.tcl" line 16)`. Without an origin there is
no file line. There is only a proc line or an eval line.

## Why the REPL keeps one command

`eval(Reader)` is the source-text primitive, but a REPL needs one result per
command. It also needs to wait when a line opens a body or a substitution.

`Repl` reads lines from the host's `Reader`, uses the same delimiter rules as
`info complete`, and sends each complete command to the evaluator through a
new `Reader`. It keeps the current command only. A large script after that
command stays unread until the evaluator returns.

Prompts are a presentation choice. The default has none, so a pipe carries
only results and errors. `interactive()` adds the primary and continuation
prompts for a terminal. The host still owns all streams and closes them.

## Why `info` exists

Scripts debug scripts. They need `info exists`, `info level`, `info frame`,
`info errorstack`, and `info commands`. A REPL needs `info complete` before
it evals a line.

The host needs the same facts. The host calls `info` as a command. A second
Java inspector would drift from the command. `Tcl.exists` and `Tcl.get` stay,
because they are the embedding `set`/`get` pair. Everything else goes through
`call("info", ...)`.

`info` does not report hostname, executable path, or a fake Tcl version. Those
are process facts. The host already knows them. The host may add
`::info::hostname` if a script needs it.

## Why there is no I/O

`puts`, `source`, `open`, `exec`, and `socket` need a process and a file
system. An embedded interpreter must not take those from the host.

The host already reads. The host already writes.

## Why the interpreter does not fork threads

Tcl 9 has no thread command in the core language. This package does the same.

Virtual threads, streams, and Flow run around the interpreter. They do not
run inside it. Two overlapping `eval` calls on one interpreter would race
the namespace tree and the frame stack.

The lock is reentrant so `if`, procs, and stream callbacks on the same
thread can nest. Another thread does not wait. Waiting deadlocks when the
running script would join that thread. The error is `TCL BUSY`.

Two request handlers that must script at the same time get two interpreters.

`Tcl.current()` is a `ScopedValue` bound during eval. A Java method called
from a script can find the interpreter without taking `Tcl` as a parameter.

Interrupt is checked between commands so a closed executor can stop a
script. A Java method already running is not aborted.

A `Stream` callback that is a Tcl command uses the same lock. Sequential
`filter` on this thread works. `parallel()` does not. `Flow.onNext` arrives
on another thread. The interpreter is not a subscriber.

## Why `foreach` exists

Without `foreach`, iteration is Java iterators. That fights the language.
`foreach` walks `Iterable`, `Iterator`, `Stream`, arrays, `Map`, and Tcl
list strings. `break` and `continue` work.

`Values.list` does not drain a `Stream`. A stream is one-shot. `foreach` on
a stream consumes it. That is explicit.

## Why this is not hyperspec

`web.hyperspec` already parses Tcl-shaped words. Every value there is a
string. `]` always ends a word. There is no `::` in `$` names. That parser
uses `peg`.

This package is the language. Hyperspec is a test DSL. A later change can
register hyperspec commands on a `Tcl`. That change is a rewrite of a working
DSL. It is not part of v1.

## Why the host does not concatenate source

A Java string `"set x " + user` quotes wrong. Spaces, braces, and the object
identity are lost. `user` would become `user.toString()`.

`invoke` takes words that are already values. `eval(List.of(List.of(...)))`
is a script of those commands. `if` / `proc` bodies accept the same lists
through `Values.script`. The host never builds Tcl source to pass an object.

`call("bump", 10)` is `invoke` with a command name. `invoke(counter, "inc")`
is a method call with no `$counter`.

## Why method dispatch is on the first word

The host passes objects. The script must call methods without a `java::`
command.

If the first word is a string, the interpreter finds a command. If the first
word is any other object, the interpreter calls a Java method. The second word
is the method name.

That rule is the whole bridge. It does not stringify the object first.

## What v1 does not include

No `list`, `lindex`, `dict`, or `eval` as a command.
`uplevel #0 $body` is eval. `foreach` walks host collections. Java methods
on `List` and `Map` remain.

No `rename`, no `unknown` as a command, no `apply`.
`namespace unknown` is the per-namespace handler.

`info` is present. It omits process, library, load, TclOO, `const`, and
coroutine subcommands.

No sandbox. A public method on an object you passed is callable. Do not pass
an object you do not want a script to call.

No `thread` command. No Tcl Flow subscriber. No parallel stream with a Tcl
callback.

## Build order

Implement in this order. Each step uses the tree. No step assumes a flat map.

1. Hand-roll the parser. One command from a character source. Qualified `$`
   names.
2. Convert values. Parse and format Tcl lists.
3. Create `Namespace`, name resolution, and `Frame`.
4. Eval from a `Reader`. Substitute. Keep the object for a lone part.
5. Add `set`, `unset`, and `incr`.
6. Add `expr`.
7. Add `if`, `while`, `for`, `switch`, `break`, and `continue`.
8. Add `proc`, `return`, `uplevel`, `upvar`, `variable`, `global`, and
   `error`.
9. Add `catch`, `try`, `throw`, and the return-options dictionary. Build
   `-errorinfo` and `-errorstack` as errors unwind.
10. Add `info` as an ensemble over `::info`.
11. Add every `namespace` subcommand. Make `namespace` an ensemble over
    `::namespace`.
12. Call Java methods. Register a host object as a command. SAM conversion
    for functional interfaces.
13. Add `foreach`.
14. Expand `{*}`. The reentrant lock, `Tcl.current()`, and interrupt checks
    belong in eval from step 4. `invoke` and `eval(List)` are dispatch
    without parse. Add them with eval.
