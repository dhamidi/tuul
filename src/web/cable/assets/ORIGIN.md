# cable

`cable-stream.js` is ours, not vendored — it lives beside `hotwired/` because it
is found the same way and served from the same load path, not because it came
from the same place.

It is the client half of `web.cable`: a Stimulus controller that opens an
`EventSource` and hands it to `Turbo.connectStreamSource`, which is how a page
applies Turbo Stream elements that arrive over a connection rather than in a
response.

An application puts this directory on its asset load path with
`web.cable.Cable.feature(Topics)`, pins it as `@tuul/cable-stream`, and registers it
under the identifier `web.cable.Cable.CONTROLLER`. All three names are constants
on `Cable`, because a mismatch between them fails silently — the page simply
never updates.
