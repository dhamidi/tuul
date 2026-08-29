# JavaDoc with Markdown

Default linking syntax for agentdoc in this repo. JavaDoc 23 and later
accept Markdown in `///` and `/**` comments. `tuul docs` prints those
comments.

## Comment form

Prefer `///` on types and members. Use `/** */` only when the file already
does.

Put prose in the comment body. Do not add `@param`, `@return`, or `@throws`
when the identifier already says that. A tag is useful only when it states
an invariant the name does not.

## Link to a member

When a sentence is about another member, write a Markdown link whose
target is that member.

| Target | Write |
|---|---|
| Member on this type | `[#name]` |
| Member with parameters, when the file already uses that form | `[#name(Duration)]` |
| Member on another type | `[OtherType#name]` |

Match the form already in the file. If the file writes `[#patience(Duration)]`,
do not write `[#patience]` in the same comment set.

The link text may be empty after the `#`. The generated docs fill the
visible name from the target.

## What not to link

- A private method. Describe the public result instead.
- A name you invented. If the member is `patience`, do not link a “wait bound.”
- `{@link}` or `{@code}` when Markdown already covers it. Stay in one syntax.

## Code in comments

Fence a program with Markdown triple backticks inside the doc comment.
Indent the fence with the `///` prefix, the same as the rest of the
comment.

Use backticks around a literal type or field name in prose (`error`,
`error.timeout`).
