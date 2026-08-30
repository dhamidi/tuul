# catch

## Syntax

`catch script ?resultVarName? ?optionsVarName?`

## Result

Return the trapped Tcl return code as a `Long`.
Store the script result and options when variable names are present.

## Errors

Raise an error for the wrong number of arguments or an invalid body value.
The command traps errors raised by the body.

## Example

```tcl
catch {error failed} message options
```

## Behavior

Eval `script` in the current frame. Trap every return code. Always return a
`Long` code. Do not let the exception leave this command.

If `resultVarName` is present, store the result of the script. On error that
value is the message.

If `optionsVarName` is present, store the return-options dictionary. `-code`
and `-level` are always present. When the code is not `2`, `-level` is `0`
and `-code` is the same as the value `catch` returns. When the code is `1`,
`-errorinfo`, `-errorcode`, `-errorline`, and `-errorstack` are present.

`catch` also writes `::errorInfo` and `::errorCode` on error.
