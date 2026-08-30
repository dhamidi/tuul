# incr

## Syntax

`incr varName ?amount?`

## Result

Return the new integer value as a `Long`.

## Errors

Raise an error when `varName` is unset, when an argument is not an integer, or when the result overflows.

## Example

```tcl
set count 2
incr count 3
```

## Behavior


`varName` must exist. Default amount is `1`. Store a `Long`. Return it.
