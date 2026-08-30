# while

## Syntax

`while test body`

## Result

Return the empty string after the loop ends.

## Errors

Raise an error for invalid arguments, an invalid test, or an invalid body.

## Example

```tcl
while {$count > 0} {incr count -1}
```

## Behavior


`test` is an expression. `body` is a script.
