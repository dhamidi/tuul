# try

## Syntax

`try body ?handler …? ?finally script?`

## Result

Return the body result or the selected handler result. Discard a successful `finally` result.

## Errors

Raise an error for malformed handlers or invalid body values.
A failing handler or `finally` script replaces the earlier result.

## Example

```tcl
try {error failed} on error {message options} {set handled $message}
```

## Behavior

Eval `body`. Then match at most one handler. Then eval `finally` if it is
present.

A handler is:

```
on code variableList script
trap pattern variableList script
```

`code` is `ok`, `error`, `return`, `break`, `continue`, or an integer. `ok`
is `0`. `error` is `1`. `return` is `2`. `break` is `3`. `continue` is `4`.

`on` matches the code of the body. `trap` matches when the code is `1` and
the error code list starts with `pattern`. `pattern` is a Tcl list of prefix
elements.

`variableList` is a Tcl list of zero, one, or two names. The first name
receives the result or the error message. The second name receives the same
options map as `catch`.

`finally` always runs after the body and after a matching handler.
If `finally` throws, that exception replaces the body and handler result.
The command stores the original options dictionary under `-during`.

A handler script that is `-` uses the next handler, as in `switch`.

The result of `try` is the result of the matching handler, or the result of
the body when no handler matches. The result of `finally` is discarded unless
`finally` throws. `on error` is `trap {}`. `on error` masks a later `trap`.
