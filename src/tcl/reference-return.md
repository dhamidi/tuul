# return

## Syntax

`return ?option value …? ?result?`

## Result

Return `result` after the requested completion reaches its target frame.
The command normally transfers control instead of returning to its caller.

## Errors

Raise an error for an unknown return code, a negative `-level`, or malformed option pairs.

## Example

```tcl
proc first {} {return done; set unreachable no}
first
```

## Behavior

Leave the current proc, or the current `eval` when there is no proc.

Default `result` is `""`. Every `option value` pair is stored in the
return-options dictionary.

Recognized options, as in Tcl 9:

| Option | Meaning |
|---|---|
| `-code code` | `ok` `error` `return` `break` `continue`, or an integer. Default `ok`. |
| `-level n` | proc frames to unwind. Default `1`. Must be `>= 0`. |
| `-errorcode list` | used when `-code` is error. Default `{NONE}`. Also stored in `::errorCode`. |
| `-errorinfo info` | initial human traceback when `-code` is error. Also stored in `::errorInfo`. |
| `-errorstack list` | initial machine stack when `-code` is error. |
| `-options dict` | a `Map`. Each entry is more option/value pairs. |

`-level` and `-code` work together. `return` itself has code `TCL_RETURN` when
`-level` is not `0`. Each proc that receives `TCL_RETURN` decrements `-level`.
When `-level` reaches `0`, that proc returns with `-code`.

`return -level 0 -code break` is `break` in the current script.
`return -code error` is `error` in the caller.

To rethrow a caught failure:

```
catch { ... } result options
return -options $options $result
```
