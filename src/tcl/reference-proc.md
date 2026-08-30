# proc

## Syntax

`proc name args body`

## Result

Return the empty string after the command registers the procedure.

## Errors

Raise an error for invalid parameters or an invalid body.

## Example

```tcl
proc add {a b} {expr {$a + $b}}
add 2 3
```

## Behavior


`args` is a Tcl list of parameters:

- `a` — required
- `{b default}` — optional. The default is source. The interpreter evals it
  when the caller omits the argument.
- `args` — last. Remaining arguments as a `List`.

`name` may be qualified. Missing namespaces are created.

The new command lives in the resolved namespace. Its origin is that
namespace.
