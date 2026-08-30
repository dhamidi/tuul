# info

The `info` command is an ensemble. Its subcommands inspect the interpreter.

## Syntax

`info subcommand ...`

## Result

Each subcommand returns the value in its section.
Numeric subcommands return `Long` values.

## Errors

Raise an error for invalid arguments.
Raise an error for an unknown procedure or command.
Raise an error for an invalid frame or level.

## Example

```tcl
info commands
info exists name
```

## Behavior

An ensemble over `::info`. Introspection of this interpreter. The host calls
it as a command: `tcl.call("info", "commands")`. There is no second Java API
for the same facts.

Follows Tcl 9 [info](https://www.tcl-lang.org/man/tcl9.0/TclCmd/info.html),
minus process, library, load, TclOO, `const`, and coroutine.

## `info args procname`

Return the parameter names of that proc, in order.

## `info body procname`

Return the body of that proc.

## `info cmdcount`

Return the number of commands this interpreter has run, a `Long`.

## `info cmdtype commandName`

Return the kind of command:

| Value | Meaning |
|---|---|
| `proc` | created by `proc` |
| `ensemble` | created by `namespace ensemble` |
| `import` | created by `namespace import` |
| `native` | a builtin, a Java `Command`, or a host object registered as a command |

Raise an error for an unknown name.

## `info commands ?pattern?`

Return visible command names in the current namespace. `pattern` uses glob.
The command searches only that namespace for a qualified `pattern`.
It returns qualified names.

## `info complete command`

Return `1` if `command` has no unclosed `{`, `"`, or `[`. Return `0`
otherwise. Use this before `eval` of a line from a `Reader`.

## `info default procname parameter varname`

If that parameter has a default, store it in `varname` and return `1`.
Else return `0`.

## `info errorstack`

Return `-errorstack` of the last error in this interpreter.

## `info exists varName`

Return `1` if `varName` is visible and set. Return `0` otherwise.
A variable that holds `null` counts as set. An unset variable does not.
This is the same fact as `Tcl.exists` from Java.

## `info frame ?depth?`

With no `depth`, return the depth of this `info frame` call, a `Long`.

With `depth`, return a `Map` for that frame.
The depth counts proc levels, `uplevel`, and body evaluation frames.
The depth is always greater than `info level`.

Positive `depth` is absolute. Non-positive is frames up from the current
frame.

Keys, when present:

| Key | Value |
|---|---|
| `type` | `source`, `proc`, or `eval` |
| `line` | line in that script |
| `file` | origin, when `type` is `source` |
| `cmd` | the command as written, before substitution |
| `proc` | proc name, when inside a proc |
| `level` | `info level` index, when this frame is a proc level |

`type` is `source` when the eval has an origin. `type` is `proc` when the
command is in a proc body that has no origin. `type` is `eval` when the
script was a string given to `uplevel` or a body without origin.

Line numbers for `source` are relative to the start of that origin. Line
numbers for `proc` and `eval` are relative to the start of that body.

There is no `precompiled` type. There is no `lambda` key. This package has
no `apply`.

## `info functions ?pattern?`

Return `expr` function names. `pattern` uses glob.

## `info globals ?pattern?`

Return names of variables in `::`. `pattern` uses glob.

## `info level ?number?`

With no `number`, return the current proc level. `0` at top level.

With `number`, return the command at that level as a list: name and
arguments after substitution. Positive `number` is absolute (`1` is the
outermost proc). Non-positive is relative (`0` is this proc, `-1` is the
caller).

## `info locals ?pattern?`

Return local names in this proc, including parameters. Names created by
`global`, `upvar`, or `variable` are not listed. `pattern` uses glob.

## `info procs ?pattern?`

Return proc names visible in the current namespace. Qualified `pattern`
selects a namespace.

## `info script ?filename?`

Return the origin of the innermost eval, or `""`. With `filename`, store
that name as the origin for the rest of the innermost eval.

The host sets origin with `eval(..., origin)`. `info script` reads it.

## `info vars ?pattern?`

Return visible variable names: locals and namespace variables. Qualified
`pattern` selects a namespace and returns qualified names. A name declared
by `variable` but not yet set is included.

`info` does not include `hostname`, `library`, `loaded`,
`nameofexecutable`, `patchlevel`, `sharedlibextension`, `tclversion`,
`class`, `object`, `constant`, `consts`, or `coroutine`. Those need a
process, a Tcl library, TclOO, or features this package does not have. The
host may register extra `::info::*` commands.
