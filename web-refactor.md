# Dissolve `web.controllers`

`web` is Go handlers + TEA pages + Hotwire documents. Rails supplies conventions (assets, CSRF, 303, import maps), not MVC types. `web.controllers` is the leftover junk drawer. Dissolve it.

Do not add a `Controller` type. `Handler` + `Routing.on` + `Page` stay the request path. Stimulus keeps the word “controller”.

## Shape after

| Goes to | Types |
|---|---|
| `web` (beside `Headers` / `Parameters`) | `Cookie`, `Cookies`, `Accept`, `Negotiate` |
| `web.sessions` | `Signature`, `Session`, `Sessions`, `Csrf` |
| `web.uploads` | `Multipart`, `Part`, `Upload`, `Uploads`, `Received`, `Limits` |
| gone | `web.controllers`, `ControllerException` |

## Finish two layering lies while moving

1. **`Negotiate.stream` takes `Markup`, not `Html`.** Same reason `Markup` exists: `web` must not depend on `web.ui`. `Responses.turbo` already takes `Markup`; `Html` implements it, so call sites stay.
2. **CSRF oversized body answers 413**, like a bad token already answers 403. Today it throws and the server turns that into 500; `ControllerException.status()` was never wired. Delete the exception instead of renaming it.

Other refusals:

- `Cookie.of` / `Signature.of` with a bad value → `IllegalArgumentException` (constructor args; tests already use `Check.throwing`)
- `Signature` when HMAC is missing → `IllegalStateException`
- `Sessions.write` “impossible” IOException → `IllegalStateException`
- `Limits` non-positive numbers → `IllegalArgumentException`
- multipart/upload refusals → `web.uploads.UploadException` with `status()` (400 vs 413), same as today

`web` still does not depend on `web.sessions` or `web.uploads`. `Middlewares` keeps repeating `64 * 1024` rather than reading `Limits`.

## Files

Move (package + imports only, comments stay):

- `Cookie.java`, `Cookies.java`, `Accept.java`, `Negotiate.java` → `src/web/`
- `Signature.java`, `Session.java`, `Sessions.java`, `Csrf.java` → `src/web/sessions/`
- `Multipart.java`, `Part.java`, `Upload.java`, `Uploads.java`, `Received.java`, `Limits.java` → `src/web/uploads/`
- add `src/web/uploads/UploadException.java`
- delete `src/web/controllers/`

Call sites / exports:

- `src/module-info.java` — drop `web.controllers`; export `web.sessions`, `web.uploads`
- `src/browser/Browser.java` — `import web.Negotiate`
- `src/web/Feature.java` — `{@link web.sessions.Sessions}`, `{@link web.sessions.Csrf}`
- `src/web/Middlewares.java` — `{@link web.uploads.Limits#fieldBytes()}`
- `test/web/FeaturesTest.java` — `web.sessions.Sessions` / `Signature`

Tests — split `ControllersTest` along the same seams:

- cookies / Accept / Negotiate → `test/web/WebTest.java` (`cookies()`, `accept()`, `negotiate()`)
- signing / sessions / CSRF → `test/web/sessions/SessionsTest.java`
- multipart / uploads → `test/web/uploads/UploadsTest.java`
- CSRF oversized: expect **413**, not 500
- `test/run.java` — replace the one suite with `web.sessions.SessionsTest` and `web.uploads.UploadsTest`

Docs (STE, match surrounding tone):

- `src/web/package-info.java` — subpackages: cookies and Accept live here; `web.sessions` is signed cookies the server can trust; `web.uploads` is multipart to disk. Drop “controllers”.
- `ARCHITECTURE.md` — replace the controllers bullet. Do not recast `web` as MVC. One line that a handler (or a `Page`) answers a request.

## Verify

`mise run test` — at least `web.WebTest`, `web.FeaturesTest`, `web.sessions.SessionsTest`, `web.uploads.UploadsTest`, `browser.BrowserTest`.
