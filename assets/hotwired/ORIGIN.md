# hotwired

[Turbo](https://turbo.hotwired.dev) and [Stimulus](https://stimulus.hotwired.dev),
vendored: the ES module builds as published, unmodified, with their licences.

    turbo     8.0.23   https://unpkg.com/@hotwired/turbo@8.0.23/dist/turbo.es2017-esm.js
    stimulus  3.2.2    https://unpkg.com/@hotwired/stimulus@3.2.2/dist/stimulus.js

Both are MIT. `LICENSE-turbo` is from the Turbo repository, since the published
package does not carry one; `LICENSE-stimulus` is the one in the package.

They are committed rather than fetched, for the same reason `native/sqlite3` is:
nothing at build or run time touches the network, and an application should not
need a package manager to get the two libraries its framework assumes.
`web.assets` serves them — `Assets.standard(...)` puts this directory on the
load path and `Importmap.standard()` pins them — so an application writes

    import { Turbo } from "@hotwired/turbo"

and nothing has to rewrite it.

The banner inside `stimulus.js` says 3.2.1 while the package is 3.2.2; that is
upstream's, left as it was shipped.

To update: download the two files from the versions you want, replace them, and
change the versions above. Nothing else records them.
