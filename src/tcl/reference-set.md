# set

## Syntax

`set varName ?value?`

## Result

With `value`, return the stored value. Without `value`, return the current value.

## Errors

Raise an error when `varName` is unset. Raise an error when a qualified parent namespace is missing.

## Example

```tcl
set count 2
incr count
```

## Behavior


With a value: store it and return it. Without a value: return the current
value. Unset is an error.

`varName` of the form `a(i)` is an array element.
