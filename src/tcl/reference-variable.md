# variable

## Syntax

`variable name ?value? …?`

## Result

Return the empty string after the command declares all names.

## Errors

Raise an error when no name is provided or when a proc cannot resolve its origin namespace.

## Example

```tcl
namespace eval app {variable name demo}
```

## Behavior

In a proc, link the local name to the variable with the same name in the
proc's origin namespace.
Create that namespace variable when it is missing.
If `value` is present, store it only when the namespace variable is unset.

Outside a proc, create or set the namespace variable in the current namespace.
