package web.uploads;

import java.nio.file.Path;

/// A file that arrived and was written down.
///
/// `stored` is where this server put it, and it is a name this server chose.
/// `suggested` is what the client called it, kept because a person recognises
/// their own filename and nothing else about the upload will mean anything to
/// them — but it is a label, and the two fields are separate so that no code
/// can use the wrong one by reaching for the obvious name.
public record Upload(String field, String suggested, String type, Path stored, long size) {}
