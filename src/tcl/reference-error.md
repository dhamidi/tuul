# error

## Syntax

`error message ?info? ?code?`

## Result

This command does not return normally. It raises an error with `message` as its result.

## Errors

The command always raises Tcl return code `1`. It can also raise an argument-count error.

## Example

```tcl
if {$count < 0} {error "negative count"}
```

## Behavior

Return code `1`. `message` is the result. `info` initializes `-errorinfo`.
When `info` is present, the command that contains `error` is not added to the
trace. `code` is `-errorcode`. Default `{NONE}`.

Prefer `return -options` to rethrow. `error $msg $savedInfo` is the old form.
