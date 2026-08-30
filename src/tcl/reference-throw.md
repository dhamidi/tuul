# throw

## Syntax

`throw type message`

## Result

This command does not return normally. It raises an error with `message` as its result.

## Errors

The command always raises Tcl return code `1`.
It raises an argument-count error for the wrong number of arguments.

## Example

```tcl
throw {APP INVALID} "invalid value"
```

## Behavior

Return code `1`. `type` is `-errorcode`, a list. `message` is the result.
Words in `type` go from general to specific.
