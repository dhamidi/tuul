package web.assets;

import java.io.FilterWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;
import json.Json;
import web.text.Escape;

/// An import map: what a bare specifier means.
///
/// This is the whole of what a bundler was doing for module resolution. The
/// browser is told that `@hotwired/turbo` is `/assets/turbo-<digest>.js`, and
/// from then on an application writes `import { Turbo } from "@hotwired/turbo"`
/// and nothing has to rewrite it.
///
/// ```
/// var map = Importmap.standard().pin("application", "application.js");
/// map.write(assets, out);
/// ```
public record Importmap(Map<String, String> pins) {

    public Importmap {
        pins = new LinkedHashMap<>(pins);
    }

    public static Importmap of() {
        return new Importmap(Map.of());
    }

    /// Turbo and Stimulus, pinned. An application that wants reactivity should
    /// not have to know the file names of the two libraries its framework is
    /// built around.
    public static Importmap standard() {
        return of()
                .pin(Hotwired.TURBO, Hotwired.TURBO_FILE)
                .pin(Hotwired.STIMULUS, Hotwired.STIMULUS_FILE);
    }

    /// Pins a bare specifier to a logical asset name. The order pins are added
    /// is the order they are written, so a map reads the way it was built.
    public Importmap pin(String module, String logical) {
        var pinned = new LinkedHashMap<>(pins);
        pinned.put(module, logical);
        return new Importmap(pinned);
    }

    /// The map itself. Resolving happens here rather than at pin time, because a
    /// pin is a name and an asset's URL is not known until its content is.
    public Json.Object json(Assets assets) {
        var imports = Json.Object.of();
        for (var pin : pins.entrySet()) imports = imports.with(pin.getKey(), assets.url(pin.getValue()));
        return Json.Object.of().with("imports", imports);
    }

    /// The tags a page needs, in the order a browser needs them: the map before
    /// anything imports through it, then a preload for each module so the
    /// browser can start fetching them without waiting to be asked.
    public void write(Assets assets, Writer out) throws IOException {
        out.write("<script type=\"importmap\">");
        json(assets).write(new Inline(out));
        out.write("</script>\n");
        for (var pin : pins.values()) {
            out.write("<link rel=\"modulepreload\" href=\"");
            Escape.attribute(assets.url(pin), out);
            out.write("\">\n");
        }
        out.flush();
    }

    /// A writer that makes a JSON document safe to sit inside a `script`
    /// element.
    ///
    /// The bytes `</script` end the element wherever they appear, including in
    /// the middle of a string, and a file name is not something this package
    /// gets to assume about. So the angle brackets leave as their JSON escapes,
    /// which a parser reads back as the same two characters.
    ///
    /// It is a writer rather than two passes over a string because the map is
    /// written into the page as it is serialised. [json.Json#text()] says it is
    /// for values known to be small, and an import map grows with every pin an
    /// application adds — it was being built whole, copied once per escape, and
    /// only then written, on the render of every page.
    private static final class Inline extends FilterWriter {

        private Inline(Writer out) {
            super(out);
        }

        @Override
        public void write(int character) throws IOException {
            switch (character) {
                case '<' -> out.write("\\u003c");
                case '>' -> out.write("\\u003e");
                default -> out.write(character);
            }
        }

        @Override
        public void write(char[] buffer, int from, int length) throws IOException {
            for (var at = from; at < from + length; at++) write(buffer[at]);
        }

        @Override
        public void write(String text, int from, int length) throws IOException {
            for (var at = from; at < from + length; at++) write(text.charAt(at));
        }
    }
}
