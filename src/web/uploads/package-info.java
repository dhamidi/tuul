/// Streaming multipart uploads that write files to disk.
///
/// [Uploads] reads a `multipart/form-data` request. It streams each file to a
/// server-chosen path and returns the ordinary fields with the stored files in
/// a [Received] value. [Multipart] gives a caller the same stream one [Part]
/// at a time when the high-level operation is not enough.
///
/// The package never uses a client filename as a path. [Part#suggested()] is a
/// display label. [Upload#stored()] is the path that the server chose.
///
/// ## Store an upload
///
/// Give [Uploads#into(web.Request,java.nio.file.Path)] a request and a private
/// directory. The method creates the directory, writes files as bytes arrive,
/// and returns fields and file metadata.
///
/// ```
/// var received = Uploads.into(request, Path.of("var/uploads"));
/// var title = received.fields().first("title", "");
/// for (var file : received.files()) {
///     System.out.println(file.suggested() + " -> " + file.stored());
/// }
/// ```
///
/// Use [Received#file(String)] or [Received#files(String)] when a form field
/// can contain one or several files. The returned paths are ready for the
/// server to read. The client label is not a destination.
///
/// ## Read parts yourself
///
/// Call [Multipart#of(web.Request)] for the default limits or
/// [Multipart#of(web.Request,Limits)] for an explicit [Limits] value. Call
/// [Multipart#next()] until it returns an empty value. Read the current
/// [Part#body()] before asking for the next part.
///
/// ```
/// try (var multipart = Multipart.of(request)) {
///     multipart.each(part -> {
///         if (part.file()) {
///             part.body().transferTo(System.out);
///         } else {
///             var value = part.text();
///         }
///     });
/// }
/// ```
///
/// [Part#body()] is valid until the next part is requested. The reader drains
/// unread bytes before it advances. This keeps the request stream aligned.
///
/// ## Limits and refusals
///
/// [Limits#DEFAULT] allows a 16 MiB part, a 32 MiB request, 64 parts, 16 KiB
/// of headers per part, and a 64 KiB ordinary field. Every limit is checked as
/// the request is read. [Limits#part(long)], [Limits#total(long)],
/// [Limits#parts(int)], and [Limits#field(long)] return changed limits.
///
/// [UploadException] reports a malformed multipart request with status 400.
/// It reports a limit refusal with status 413 through [UploadException#status()].
/// [Multipart#of(web.Request)] also refuses a request whose content type is not
/// `multipart/form-data` or whose content type has no boundary.
///
/// ## The types in this package
///
///   - [Uploads] writes files and returns [Received].
///   - [Multipart] reads one stream of [Part] values.
///   - [Part] exposes headers, the client label, and one part body.
///   - [Upload] records a stored path, display label, media type, and size.
///   - [Received] holds [web.Parameters] and the files from one request.
///   - [Limits] bounds the stream. [UploadException] describes a refusal.
///
/// ## Surprises worth knowing
///
///   - **The whole upload is not in memory.** The parser keeps a small buffer
///     and one current part. The high-level writer copies bytes to disk.
///   - **The suggested filename is not safe as a path.** It is safe to show a
///     person after [Part#suggested()] sanitises it. [Uploads] still chooses a
///     random stored name.
///   - **Unread part bytes are discarded.** Asking for the next part drains the
///     current part, so a caller cannot return to an earlier part body.
///   - **A field is bounded too.** [Part#text()] refuses a field larger than
///     `Limits#fieldBytes()` instead of reading an unbounded string.
package web.uploads;
