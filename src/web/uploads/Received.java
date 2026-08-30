package web.uploads;

import java.util.List;
import java.util.Optional;
import web.Parameters;

/// What came out of a form that had files in it.
///
/// The ordinary fields are [web.Parameters], the same type a query string and a
/// plain form arrive as, so a handler reads them the same way whether or not
/// the form happened to include an upload.
public record Received(Parameters fields, List<Upload> files) {

    public Received {
        files = List.copyOf(files);
    }

    /// The files sent under one field name — several, because one `<input
    /// multiple>` is one name and many files.
    public List<Upload> files(String field) {
        return files.stream().filter(upload -> upload.field().equals(field)).toList();
    }

    public Optional<Upload> file(String field) {
        return files(field).stream().findFirst();
    }
}
