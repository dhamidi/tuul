# import

## Syntax

`import className ?as name? ?className ?as name? …?`

## Result

Return the list of command names that the command registered.

## Errors

Raise an error with code `TCL LOOKUP CLASS className` when the class does
not exist, is not public, or is not permitted by the host.
Raise an error when `as` has no name after it.

## Example

```tcl
import json.Json java.util.ArrayList
set tags [ArrayList new]
$tags add [Json.Str new admin]
```

## Behavior

Register each class as a command in the current namespace. The command
name is the simple name of the class, or `name` after `as`.

Register each public member class of that class as `Outer.Inner`.
`import json.Json` registers `Json`, `Json.Array`, `Json.Bool`,
`Json.Null`, `Json.Num`, `Json.Object`, and `Json.Str`.

Write `className` as Java writes it. `json.Json.Str` finds the member class
`Str` of `json.Json`. Do not write the binary name with `$`. A `$` in a bare
word substitutes a variable.

A class command runs a constructor or a static method. See
[Class commands](reference-runtime.md#class-commands).

The host permits classes with `imports(patterns…)`, or passes them with
`types(classes…)`. `import` registers only a permitted class. The default
permits none. See [`Tcl`](reference-runtime.md#tcl).

`import` searches the named modules in the layer supplied to `Tcl.of(layer)`
and its parents. `Tcl.of()` uses the boot layer. It does not search the thread
context class loader, a class path, or an unnamed module. A host with a
generation layer must construct the interpreter with that layer.

`import` does not initialize the class. Static initializers run at the first
`new` or static method call.

A second `import` under the same name replaces the first.
