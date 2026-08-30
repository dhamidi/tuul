# global

## Syntax

`global name …`

## Result

Return the empty string after the command links all names to the root namespace.

## Errors

Raise an error when no variable name is provided or when a link cannot be created.

## Example

```tcl
set count 0
proc increment {} {global count; incr count}
```

## Behavior

For each name, link the local to the same name at level `#0`.
