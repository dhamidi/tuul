# namespace

The `namespace` command is an ensemble.
Its subcommands manage namespaces, imports, paths, and ensembles.

## Syntax

`namespace subcommand ...`

## Result

Each subcommand returns the value in its section.
Mutating subcommands return the empty string unless the section says otherwise.

## Errors

Subcommands raise errors for invalid arguments.
They raise errors for unknown namespaces or commands.
They raise errors for invalid ensemble options.
Each section can define additional errors.

## Example

```tcl
namespace eval app {set name demo}
namespace current
```

## `namespace eval name arg …`

Create `name` and its missing parents.
Push a non-proc frame for that namespace.
Eval the joined arguments. Return the result. Pop the frame.

## `namespace current`

Return the fully qualified name of the current namespace.
The root namespace is `::`.

## `namespace parent ?name?`

Return the fully qualified parent.
The parent of `::` is `""`.
The default `name` is the current namespace.

## `namespace children ?name? ?pattern?`

Return fully qualified child names, in insertion order. `pattern` uses `*`
and `?`.

## `namespace exists name`

Return `1` or `0`.

## `namespace delete name …`

Delete each namespace and its children, commands, and variables.
Raise an error if `name` is `::`.
Raise an error if a frame on the stack uses that namespace or a
child of it.

## `namespace tail name`

Return the last part. `namespace tail ::foo::bar` is `bar`.

## `namespace qualifiers name`

Return the prefix. `namespace qualifiers ::foo::bar` is `::foo`.
`namespace qualifiers foo` is `""`.

## `namespace which ?-command|-variable? name`

Return the fully qualified name that resolution finds. Return `""` if
resolution finds nothing. Default is `-command`.

## `namespace origin cmd`

Return the fully qualified origin of `cmd`.
Follow an import to its original command.

## `namespace export ?-clear? ?pattern …?`

Add patterns to the export list of the current namespace. `-clear` empties
the list first. Patterns use `*` and `?`.

## `namespace import ?-force? ns::pattern …`

For each matching exported name, install a command in the current namespace.
The new `CommandRef` keeps the source origin.
Raise an error if the name exists, unless `-force` is present.

## `namespace forget name …`

Remove imported commands.

## `namespace upvar ns other local …`

Link each `local` in the current frame to `other` in namespace `ns`.

## `namespace path ?namespaceList?`

With no argument, return the path of the current namespace as a list of
fully qualified names.

With `namespaceList`, store that list as the path.
Resolve each element as a namespace name.
Raise an error for a missing namespace. The path does not create namespaces.

## `namespace unknown ?script?`

With no argument, return the unknown handler of the current namespace.
An empty result means that no handler exists.

With `script`, store that script as the handler. Empty `script` clears it.

The handler runs when command resolution finds nothing. The arguments are
the command name and the rest of argv. The result of the handler is the
result of the original call.

## `namespace code script`

Return a script that evals `script` in the current namespace.
The result is a `namespace inscope` command.
Eval that result from any namespace to run `script` in the captured namespace.

## `namespace inscope name arg …`

Eval in namespace `name`. Do not create `name`.
Raise an error when `name` is missing.

The first `arg` is a list prefix. Remaining `arg` values append as list
elements. The joined list is the script. This is not space concatenation.
`namespace eval` concatenates with spaces. `namespace inscope` builds a
list.

## `namespace ensemble create ?option value …?`

Create an ensemble command.
The default name is the fully qualified name of the current namespace.
The command dispatches on its first argument.
It finds a subcommand and calls it with the remaining arguments.

Options:

| Option | Meaning |
|---|---|
| `-command name` | the command to create. May be qualified. |
| `-map dict` | subcommand name to prefix. A prefix is a command and optional arguments. |
| `-subcommands list` | allowed subcommand names. Empty means: use the map keys, or all commands in the namespace. |
| `-prefixes bool` | unique prefix of a subcommand matches. Default is true. |
| `-parameters list` | extra argument names consumed before the subcommand. |
| `-unknown prefix` | command prefix called when the subcommand is missing. |

If `-map` is absent, the ensemble uses the commands in the current
namespace.

`namespace` itself is an ensemble over `::namespace`.
Each subcommand in this document is a command in `::namespace`.
`namespace eval` and
`namespace::eval` both work.

## `namespace ensemble configure command ?option? ?value option value …?`

Read or write the options of an ensemble.
With one `option` and no value, return that option.
With no option, return a dict of all options.
With pairs, store them.

Raise an error when `command` is not an ensemble.

## `namespace ensemble exists command`

Return `1` if `command` is an ensemble, else `0`.

The Java implementation stores `namespace` as an ensemble command.
It does not hide subcommands behind a switch.
