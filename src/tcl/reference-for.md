# for

## Syntax

`for start test next body`

## Result

Return the empty string after the loop ends.

## Errors

Raise an error for invalid arguments, an invalid test, or an invalid body.

## Example

```tcl
for {set i 0} {$i < 3} {incr i} {set last $i}
```

## Behavior


`start` and `next` are scripts. `test` is an expression.
