# uplevel

## Syntax

`uplevel ?level? arg …`

## Result

Return the result of the script in the target frame.

## Errors

Raise an error for an invalid level, a missing script, or an invalid script.

## Example

```tcl
proc readGlobal {} {uplevel #0 {set count}}
```

## Behavior

Eval in another frame. Several `arg` values join with one space.
