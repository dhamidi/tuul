# web.reload

Use these adapters when an HTTP server must serve or receive reloadable code.

`ReloadHandler` leases one active generation for each request. It reads the
handler stored under `ReloadHandler.HANDLER` and answers `503` before activation
or when the generation has no handler.

`JdkReloadHandler` serves an external named module that provides exactly one
`com.sun.net.httpserver.HttpHandler`. `JdkGenerationFactory` discovers that
service in each fresh `ModuleLayer`. The application needs only `jdk.httpserver`;
it does not import Tuul or `web`. A closeable provider closes after its last
leased request drains.

`HttpRevisionSource` accepts a multipart revision upload and submits a staged
`reload.Revision`. It does not compile or activate uploaded code.

Read [tutorial.md](tutorial.md) to run `tuul dev`. Read [howto.md](howto.md)
for setup steps. Read [reference.md](reference.md) for request fields and
failure statuses.
