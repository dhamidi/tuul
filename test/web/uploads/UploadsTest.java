package web.uploads;

import harness.Check;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import web.Headers;
import web.Request;
import web.serve.Memory;

public final class UploadsTest {

    private UploadsTest() {}

    public static void run() throws Exception {
        multipart();
        uploads();
    }

    private static void multipart() throws Exception {
        var body = "--X\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                + "a note\r\n"
                + "--X\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"notes.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "line one\r\nline two\r\n"
                + "--X--\r\n";

        var parts = attempted(parse(body, 0));
        Check.equal("every part arrives", 2, parts.size());
        Check.equal("a field is a field", "title|a note", parts.get(0));
        Check.equal("a file keeps its name and its type", "file|notes.txt|text/plain", parts.get(1));

        var dripped = attempted(Multipart.of(request(body, "X", 1)));
        Check.equal("and a body arriving one byte at a time reads the same",
                parts, dripped);

        var split = attempted(Multipart.of(request(body, "X", 3)));
        Check.equal("as does one whose boundary straddles two reads", parts, split);

        Check.equal("a name that is not ASCII survives the encoding browsers use for it",
                "café.txt", named("filename*=UTF-8''caf%C3%A9.txt"));
        Check.equal("a quoted name with an escape in it is read as written",
                "a\"b.txt", named("filename=\"a\\\"b.txt\""));

        refuses("a body that ends in the middle of a part is refused",
                () -> read(parse("--X\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nunfinished", 0)));
        refuses("and one whose part has no name",
                () -> read(parse("--X\r\nContent-Type: text/plain\r\n\r\nx\r\n--X--\r\n", 0)));
        Check.throwing("a request that is not multipart at all is refused",
                () -> Multipart.of(Memory.post("/", "a=1")));
        Check.throwing("and one with no boundary to split on",
                () -> Multipart.of(Request.of("POST", "/",
                        Headers.of("Content-Type", "multipart/form-data"), Request.body(""))));

        refuses("more parts than the limit allows is refused",
                () -> read(Multipart.of(request(body, "X", 0), Limits.DEFAULT.parts(1))));
    }

    private static void uploads() throws Exception {
        var directory = Files.createTempDirectory("tuul-uploads");
        directory.toFile().deleteOnExit();

        var received = Uploads.into(request(upload("../../etc/passwd", "secrets"), "X", 0), directory);
        Check.equal("the file arrives", 1, received.files().size());
        var upload = received.file("file").orElseThrow();
        Check.equal("with what was in it", "secrets", Files.readString(upload.stored()));
        Check.equal("and how much of it there was", 7L, upload.size());
        Check.equal("the fields beside it arrive too", "a note", received.fields().first("title", ""));

        Check.that("a hostile name lands inside the directory it was given",
                upload.stored().getParent().equals(directory));
        Check.that("under a name this server chose",
                upload.stored().getFileName().toString().matches("[0-9a-f]{32}"));
        Check.that("and nothing called passwd exists anywhere near it",
                Files.list(directory).noneMatch(path -> path.getFileName().toString().contains("passwd")));
        Check.equal("while the client's name is kept as a label, and only that",
                "passwd", upload.suggested());

        var extension = Uploads.into(request(upload("holiday.JPEG", "bytes"), "X", 0), directory)
                .file("file").orElseThrow();
        Check.that("an ordinary extension is kept, rebuilt rather than taken",
                extension.stored().getFileName().toString().endsWith(".jpeg"));
        var odd = Uploads.into(request(upload("thing.p h p", "bytes"), "X", 0), directory)
                .file("file").orElseThrow();
        Check.that("and one that is not plainly an extension is dropped",
                odd.stored().getFileName().toString().matches("[0-9a-f]{32}"));

        var big = "x".repeat(200_000);
        var large = Uploads.into(request(upload("big.bin", big), "X", 0), directory).file("file").orElseThrow();
        Check.equal("a file larger than any buffer arrives whole", 200_000L, large.size());
        Check.equal("byte for byte", big, Files.readString(large.stored()));

        var refused = Files.createTempDirectory("tuul-refused");
        refused.toFile().deleteOnExit();
        var counted = new Counting(bytes(upload("big.bin", big)));
        refuses("a file over the limit is refused",
                () -> Uploads.into(request(counted, "X"), refused, Limits.DEFAULT.part(1000)));
        Check.that("before the rest of it has been read — " + counted.read + " bytes of 200,000",
                counted.read < 100_000);
        Check.that("and nothing it half-wrote is left behind",
                Files.list(refused).findAny().isEmpty());

        refuses("a body over the total is refused as well",
                () -> Uploads.into(request(upload("big.bin", big), "X", 0), directory,
                        Limits.DEFAULT.total(5000)));
    }

    private static Multipart parse(String body, int drip) {
        return Multipart.of(request(body, "X", drip));
    }

    private static Request request(String body, String boundary, int drip) {
        return request(drip == 0 ? new ByteArrayInputStream(bytes(body)) : new Drip(bytes(body), drip), boundary);
    }

    private static Request request(InputStream body, String boundary) {
        return new Request("POST", "/upload", web.Parameters.NONE,
                Headers.of("Content-Type", "multipart/form-data; boundary=" + boundary), body, "",
                java.util.Map.of());
    }

    /// What a browser sends for a form with a file in it.
    private static String upload(String filename, String content) {
        return "--X\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\na note\r\n"
                + "--X\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n" + content + "\r\n--X--\r\n";
    }

    /// Reads every part, or answers with what went wrong — so a regression in
    /// the parser fails one check rather than throwing out of the whole run and
    /// taking every package tested after this one with it.
    private static List<String> attempted(Multipart multipart) {
        try {
            return read(multipart);
        } catch (Exception failure) {
            return List.of("failed: " + failure.getMessage());
        }
    }

    private static List<String> read(Multipart multipart) throws IOException {
        var parts = new java.util.ArrayList<String>();
        multipart.each(part -> parts.add(part.file()
                ? part.name() + "|" + part.filename().orElse("") + "|" + part.type()
                : part.name() + "|" + part.text()));
        return parts;
    }

    private static String named(String disposition) throws IOException {
        var body = "--X\r\nContent-Disposition: form-data; name=\"file\"; " + disposition + "\r\n\r\nx\r\n--X--\r\n";
        var multipart = parse(body, 0);
        return multipart.next().orElseThrow().filename().orElse("");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void refuses(String what, Body body) {
        try {
            body.run();
            Check.that(what + " — nothing was refused", false);
        } catch (UploadException expected) {
            Check.that(what, true);
        } catch (Exception wrong) {
            Check.that(what + " — refused with " + wrong, false);
        }
    }

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    /// A stream that answers in small pieces, so a boundary lands across two
    /// reads and the reader has to cope.
    private static final class Drip extends InputStream {

        private final byte[] bytes;
        private final int most;
        private int at;

        private Drip(byte[] bytes, int most) {
            this.bytes = bytes;
            this.most = most;
        }

        @Override
        public int read() {
            return at < bytes.length ? bytes[at++] & 0xff : -1;
        }

        @Override
        public int read(byte[] into, int offset, int length) {
            if (at >= bytes.length) return -1;
            var count = Math.min(Math.min(most, length), bytes.length - at);
            System.arraycopy(bytes, at, into, offset, count);
            at += count;
            return count;
        }
    }

    /// A stream that remembers how much of it was wanted, which is how a test
    /// tells enforcement from inspection.
    private static final class Counting extends InputStream {

        private final byte[] bytes;
        private int at;
        private int read;

        private Counting(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read() {
            if (at >= bytes.length) return -1;
            read++;
            return bytes[at++] & 0xff;
        }

        @Override
        public int read(byte[] into, int offset, int length) {
            if (at >= bytes.length) return -1;
            var count = Math.min(length, bytes.length - at);
            System.arraycopy(bytes, at, into, offset, count);
            at += count;
            read += count;
            return count;
        }
    }
}
