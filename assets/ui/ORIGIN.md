# web.ui assets

`ui.css` and `sidebar.js` are ours, not vendored from anywhere. They ship the
way Turbo and Stimulus do — beside the code, found by `Assets.standard` — so an
application gets the design and the one piece of behaviour it needs by pinning
them rather than by copying them into its own tree.

`sidebar.js` is a Stimulus controller. Stimulus itself is vendored in
`assets/hotwired/`, with its own provenance.
