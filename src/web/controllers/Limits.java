package web.controllers;

/// What an upload is allowed to be.
///
/// These are the numbers a server has to have an answer for before a byte
/// arrives, because every one of them is a way to use a server up: a part that
/// never ends, a body of ten thousand parts, a header line the length of a
/// disk. A default that is generous is not kindness — it is the setting nobody
/// changes.
///
/// They are enforced while reading rather than after. A limit checked once the
/// body is in hand has already been exceeded by the time it says so.
public record Limits(long partBytes, long totalBytes, int parts, int headerBytes, long fieldBytes) {

    /// Enough for a photograph and a form around it, and nowhere near enough to
    /// be a way to fill a disk.
    public static final Limits DEFAULT = new Limits(16 * 1024 * 1024, 32 * 1024 * 1024, 64, 16 * 1024, 64 * 1024);

    public Limits {
        if (partBytes <= 0 || totalBytes <= 0 || parts <= 0 || headerBytes <= 0 || fieldBytes <= 0) {
            throw new ControllerException("every upload limit has to be a positive number: " + partBytes + ", "
                    + totalBytes + ", " + parts + ", " + headerBytes + ", " + fieldBytes);
        }
    }

    public Limits part(long partBytes) {
        return new Limits(partBytes, totalBytes, parts, headerBytes, fieldBytes);
    }

    public Limits total(long totalBytes) {
        return new Limits(partBytes, totalBytes, parts, headerBytes, fieldBytes);
    }

    public Limits parts(int parts) {
        return new Limits(partBytes, totalBytes, parts, headerBytes, fieldBytes);
    }

    public Limits field(long fieldBytes) {
        return new Limits(partBytes, totalBytes, parts, headerBytes, fieldBytes);
    }

    /// The failure, in the same words wherever it is raised, with the status
    /// that says a client sent too much rather than something wrong.
    static ControllerException exceeded(String what, long limit) {
        return new ControllerException(what + " is larger than the " + limit + " bytes this server accepts", 413);
    }
}
