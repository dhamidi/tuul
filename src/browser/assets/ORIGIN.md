# browser

The client side of `tuul browse`, written here rather than vendored: it is this
application's own wiring, not somebody else's library.

- `search.js` — a Stimulus controller that debounces search-as-you-type.
- `page-navigation.js` — a Stimulus controller that marks the current tree row
  and builds the outline for the document in the content pane.
- `kind.js` — a Stimulus controller that opens a result's kind chip into a badge.
- `browser.css` — the stylesheet.
- `search.svg` — the Lucide Search icon used by the form submit button.

`search.svg` is copied from Lucide at commit
`dce50dd0c9d6d55dde2a8880732bbe2acc6ba29e`:
https://github.com/lucide-icons/lucide/blob/dce50dd0c9d6d55dde2a8880732bbe2acc6ba29e/icons/search.svg
It is derived from Feather. `LICENSE-lucide-search` carries the ISC and MIT
notices from Lucide's license.

They sit beside `hotwired/` and `cable/` so `Assets.standard` finds them the
same way it finds those, by asking the code where it is rather than the shell.
