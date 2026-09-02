# instanceof

## Syntax

`instanceof value class`

## Result

Return `true` when `value` is an instance of `class`. Return `false`
otherwise. `null` is not an instance of any class.

## Errors

Raise an error with code `TCL LOOKUP CLASS class` when `class` is not a
class command and not a `Class` object.

## Example

```tcl
if {[instanceof $v Json.Str]} {
    $v value
}
```

## Behavior

`class` is the name of a class command, as `import` or `types` registered
it, or a `Class` object, as `$v getClass` returns.

The result is a `Boolean`. `if`, `while`, and `expr` accept it.

For one body per class, use `switch -instanceof`. See
[switch](reference-switch.md).
