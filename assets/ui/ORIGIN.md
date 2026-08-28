# ui

The design system's stylesheet: what the `ui-` class names that `web.ui.Ui`
emits actually mean.

Ours, not vendored — there is nothing here to update from anywhere else. It
ships beside the code for the same reason Turbo and the cable's Stimulus
controller do: `Assets.standard` puts every shipped asset directory on the load
path, so an application that pins `ui.css` gets the design without copying it
into its own tree and letting the two drift.

Tokens are declared once at the top. A component spends a token and never
writes a colour or a length of its own, which is what makes the dark palette
twelve lines instead of a second stylesheet.
