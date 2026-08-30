# unset

## Syntax

`unset ?-nocomplain? varName …`

## Result

Return the empty string after the command removes all selected variables.

## Errors

Raise an error for a missing variable unless `-nocomplain` is the first argument.

## Example

```tcl
unset -nocomplain temporary
```

## Behavior


Remove each name. Missing is an error unless `-nocomplain` is the first
argument.
