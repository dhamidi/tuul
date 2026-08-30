# continue

## Syntax

`continue`

## Result

Return control to the nearest enclosing loop. The loop starts its next iteration.

## Errors

Raise an error when no enclosing `while`, `for`, or `foreach` can intercept the completion.

## Example

```tcl
foreach item {one skip three} {if {$item eq skip} {continue}}
```

## Behavior

Skip the rest of the current `while`, `for`, or `foreach` body. If no loop
intercepts, that is an error unless `catch` or `try` intercepts.
