# browser

The client side of `tuul browse`, written here rather than vendored: it is this
application's own wiring, not somebody else's library.

- `search.js` — a Stimulus controller that debounces search-as-you-type.
- `kind.js` — a Stimulus controller that opens a result's kind chip into a badge.
- `browser.css` — the stylesheet.

They sit beside `hotwired/` and `cable/` so `Assets.standard` finds them the
same way it finds those, by asking the code where it is rather than the shell.
