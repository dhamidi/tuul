# upvar

## Syntax

`upvar ?level? other local ?other local …?`

## Result

Return the empty string after the command creates all links.

## Errors

Raise an error for an invalid level, an odd number of name arguments, or a link cycle.

## Example

```tcl
proc increment {name} {upvar #0 $name value; incr value}
```

## Behavior

Link each `local` in the current frame to `other`.
