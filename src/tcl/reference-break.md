# break

## Syntax

`break`

## Result

Return control to the nearest enclosing loop. The loop returns the empty string.

## Errors

Raise an error when no enclosing `while`, `for`, or `foreach` can intercept the completion.

## Example

```tcl
foreach item {one stop three} {if {$item eq stop} {break}}
```

## Behavior

Leave the current `while`, `for`, or `foreach`. If no loop intercepts, that
is an error unless `catch` or `try` intercepts.
