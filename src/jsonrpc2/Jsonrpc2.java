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

    /// What every call and every response declares. A message that says
    /// anything else is not this protocol.
    public static final String VERSION = "2.0";

    private Jsonrpc2() {}

    /// One document, and how it arrived.
    ///
    /// `batched` is true only when the document was a JSON array. The
    /// difference is not cosmetic. A batch is answered with an array, and a
    /// single call is answered with an object, so the answer must remember the
    /// shape of the question.
    ///
    /// An empty batch is `batched` with no members. The spec makes that an
    /// invalid request, and the caller reports it.
    public record Document(boolean batched, List<Json> members) {

        public Document {
            members = List.copyOf(members);
        }
    }

    /// Reads one document from `in`.
    ///
    /// The reader is pulled by [JsonReader], so the document is parsed as it
    /// arrives and no string of it is ever built. The members are kept as
    /// values because a batch cannot be answered before all of it is read.
    ///
    /// Framing is not this method's job. It reads one value and stops. A
    /// [Transport] decides where one document stops and the next one starts.
    ///
    /// Throws a [Rejection] that carries error -32700 when the text is not
    /// JSON. That is the one error the protocol reports before it knows
    /// anything about the message.
    public static Document read(Reader in) {
        try {
            var value = new JsonReader(in).readValue();
            if (value instanceof Json.Array(var members)) return new Document(true, members);
            return new Document(false, List.of(value));
        } catch (JsonException e) {
            throw Rejection.of(Failure.parseError(e.getMessage()));
        }
    }

    /// Writes the answer to one document, and says whether it wrote anything.
    ///
    /// No responses means no document. This is the rule that makes a batch of
    /// notifications silent, and it is a rule about bytes, not about empty
    /// arrays. An empty array is a document, and the spec forbids sending one
    /// here.
    ///
    /// The writer is flushed but stays open. Whoever opened it closes it, and
    /// on most transports that close is what ends the message.
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
