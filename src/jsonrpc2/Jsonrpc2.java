package jsonrpc2;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.List;
import json.Json;
import json.JsonException;
import json.JsonReader;
import json.JsonWriter;

/// JSON-RPC 2.0, at the level of one document.
///
/// The protocol has two layers, and this file is the lower one. A document is
/// one JSON value that arrives or leaves as a unit. It holds one call, or one
/// response, or an array of them. [Server] and [Client] are the upper layer.
/// They decide what the members mean.
///
/// A batch is the reason this layer exists. The members of a batch must all be
/// read before any of them runs, and all of their answers go into one array.
/// Both directions need that, so neither owns it.
public final class Jsonrpc2 {

    /// What every call and every response declares. A call or a response that
    /// says anything else is not this protocol.
    public static final String VERSION = "2.0";

    private Jsonrpc2() {}

    /// One document, and how it arrived.
    ///
    /// `batched` is true only when the document was a JSON array. The
    /// difference is not cosmetic. A server answers a batch with an array, and
    /// a single call with an object. The answer must therefore remember the
    /// shape of the question.
    ///
    /// An empty batch is a document with `batched` true and no members. The
    /// protocol makes that an invalid request, and the caller reports it.
    public record Document(boolean batched, List<Json> members) {

        public Document {
            members = List.copyOf(members);
        }
    }

    /// Reads one document from `in`.
    ///
    /// [JsonReader] pulls the reader, so it parses the document as the
    /// document arrives. Nothing builds a string of the whole document. This
    /// method keeps the members as values, because a server cannot answer a
    /// batch before it has read every member.
    ///
    /// Framing is not this method's job. It reads one value and stops. A
    /// [Transport] decides where one document stops and the next one starts.
    ///
    /// Throws a [Rejection] that carries error -32700 when the text is not
    /// JSON. That is the one error the protocol reports before it knows
    /// anything about the document.
    public static Document read(Reader in) {
        try {
            var value = new JsonReader(in).readValue();
            if (value instanceof Json.Array(var members)) return new Document(true, members);
            return new Document(false, List.of(value));
        } catch (JsonException e) {
            throw Rejection.of(Failure.parseError(e.getMessage()));
        }
    }

    /// Writes the answer to one document. Answers with `true` when it wrote a
    /// document, and with `false` when it wrote nothing.
    ///
    /// No responses means no document. This is the rule that makes a batch of
    /// notifications silent, and it is a rule about bytes, not about empty
    /// arrays. An empty array is a document, and the protocol forbids sending
    /// one here.
    ///
    /// This method flushes the writer and leaves it open. Whoever opened it
    /// closes it, and on most transports that close is what ends the
    /// document.
    public static boolean write(List<Response> responses, boolean batched, Writer out) throws IOException {
        if (responses.isEmpty()) return false;
        var writer = new JsonWriter(out);
        if (!batched) {
            responses.getFirst().write(writer);
            writer.flush();
            return true;
        }
        writer.beginArray();
        for (var response : responses) response.write(writer);
        writer.endArray();
        writer.flush();
        return true;
    }
}
