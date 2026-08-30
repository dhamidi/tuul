# if

## Syntax

`if expr ?then? body ?elseif expr ?then? body …? ?else body?`

## Result

Return the result of the selected body.
Return the empty string when no test is true and no `else` body exists.

## Errors

Raise an error for a malformed clause, an invalid expression, or an invalid body value.

## Example

```tcl
if {$count > 0} {set state active} else {set state empty}
```

## Behavior


Each test is an expression string, or a value `Values.bool` accepts. `then`
is optional and has no meaning. Each body is `Values.script`.
