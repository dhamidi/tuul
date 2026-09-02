# Linking in other ecosystems

The default reference is [JavaDoc with Markdown](javadoc-markdown.md). Use
this file for the others. In every ecosystem the rules are the same: match
the form the file already uses, link a member when a sentence is about it,
never link a private member, and stay in one syntax.

## Python docstrings

- Comment form: a triple-quoted string as the first statement. First line
  is the first sentence. Blank line, then paragraphs.
- Link to a member: a backticked name, `` `advance` ``. Sphinx projects
  use `` :meth:`advance` `` or `` :class:`Catalog` ``. Use the form the
  project already uses.
- Do not add a `:param:` or `:returns:` line that restates the name. Put
  the contract in prose.

## Go doc comments

- Comment form: `//` lines directly above the declaration. The first
  sentence starts with the identifier by convention: `Any answers ...`.
  That is the one ecosystem where the identifier opens the sentence. Keep
  the rest of the first-sentence rule: say when, not the signature.
- Link to a member: `[Name]` for a symbol in this package, `[pkg.Name]`
  for another package. Go 1.19 and later render these.
- A code block is an indented block after a blank comment line.

## Rust

- Comment form: `///` on items, `//!` for the module.
- Link to a member: `` [`name`] `` or `` [`Type::name`] ``. Rustdoc
  resolves the path.
- Sections `# Panics`, `# Errors`, `# Examples` are conventional. Use
  `# Errors` for each error variant the function returns. A `# Examples`
  block is compiled as a test, so it must be a program.

## TypeScript and JavaScript (TSDoc, JSDoc)

- Comment form: `/** */` above the declaration.
- Link to a member: `{@link name}` or `{@link Type.name}`. Do not mix with
  Markdown link syntax in the same comment.
- Do not add `@param` or `@returns` that restate the type annotation. Keep
  a tag only when it states an invariant the signature does not.

## Markdown the docs tool prints

- Link to a symbol the way the docs tool resolves it. In tuul, a bare
  `[Type#member]` label resolves through the symbol index. A relative link
  to another document uses the file name, `[how-to](howto.md)`.
- A fenced block is a program or a transcript. Do not fence a sentence.
