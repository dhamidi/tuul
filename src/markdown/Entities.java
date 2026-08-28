package markdown;

import java.util.Map;

/// Character references — `&amp;`, `&#42;`, `&#x2A;`.
///
/// Numeric references are handled in full: any code point, with the
/// specification's rule that zero and anything out of range becomes the
/// replacement character. Named references are a **subset** — the hundred or so
/// that appear in prose, not the two thousand HTML5 defines — and a name that
/// is not in it is left exactly as it was written, which is what a browser does
/// with one it does not know. That is the one place this library knowingly
/// answers less than the specification asks, and it is written down here rather
/// than discovered.
final class Entities {

    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""),
            Map.entry("apos", "'"), Map.entry("nbsp", " "), Map.entry("copy", "©"),
            Map.entry("reg", "®"), Map.entry("trade", "™"), Map.entry("hellip", "…"),
            Map.entry("mdash", "—"), Map.entry("ndash", "–"), Map.entry("lsquo", "‘"),
            Map.entry("rsquo", "’"), Map.entry("ldquo", "“"), Map.entry("rdquo", "”"),
            Map.entry("laquo", "«"), Map.entry("raquo", "»"), Map.entry("deg", "°"),
            Map.entry("plusmn", "±"), Map.entry("times", "×"), Map.entry("divide", "÷"),
            Map.entry("frac12", "½"), Map.entry("frac14", "¼"), Map.entry("frac34", "¾"),
            Map.entry("sup2", "²"), Map.entry("sup3", "³"), Map.entry("micro", "µ"),
            Map.entry("para", "¶"), Map.entry("sect", "§"), Map.entry("dagger", "†"),
            Map.entry("Dagger", "‡"), Map.entry("bull", "•"), Map.entry("middot", "·"),
            Map.entry("euro", "€"), Map.entry("pound", "£"), Map.entry("yen", "¥"),
            Map.entry("cent", "¢"), Map.entry("larr", "←"), Map.entry("rarr", "→"),
            Map.entry("uarr", "↑"), Map.entry("darr", "↓"), Map.entry("harr", "↔"),
            Map.entry("alpha", "α"), Map.entry("beta", "β"), Map.entry("gamma", "γ"),
            Map.entry("delta", "δ"), Map.entry("pi", "π"), Map.entry("sigma", "σ"),
            Map.entry("omega", "ω"), Map.entry("Omega", "Ω"), Map.entry("lambda", "λ"),
            Map.entry("mu", "μ"), Map.entry("infin", "∞"), Map.entry("ne", "≠"),
            Map.entry("le", "≤"), Map.entry("ge", "≥"), Map.entry("asymp", "≈"),
            Map.entry("equiv", "≡"), Map.entry("sum", "∑"), Map.entry("prod", "∏"),
            Map.entry("radic", "√"), Map.entry("int", "∫"), Map.entry("part", "∂"),
            Map.entry("nabla", "∇"), Map.entry("isin", "∈"), Map.entry("notin", "∉"),
            Map.entry("cap", "∩"), Map.entry("cup", "∪"), Map.entry("sub", "⊂"),
            Map.entry("sup", "⊃"), Map.entry("and", "∧"), Map.entry("or", "∨"),
            Map.entry("not", "¬"), Map.entry("check", "✓"), Map.entry("star", "☆"),
            Map.entry("hearts", "♥"), Map.entry("diams", "♦"), Map.entry("clubs", "♣"),
            Map.entry("spades", "♠"), Map.entry("aacute", "á"), Map.entry("eacute", "é"),
            Map.entry("iacute", "í"), Map.entry("oacute", "ó"), Map.entry("uacute", "ú"),
            Map.entry("agrave", "à"), Map.entry("egrave", "è"), Map.entry("ccedil", "ç"),
            Map.entry("ntilde", "ñ"), Map.entry("uuml", "ü"), Map.entry("ouml", "ö"),
            Map.entry("auml", "ä"), Map.entry("szlig", "ß"), Map.entry("aring", "å"),
            Map.entry("oslash", "ø"), Map.entry("aelig", "æ"), Map.entry("thinsp", " "),
            Map.entry("ensp", " "), Map.entry("emsp", " "), Map.entry("zwj", "‍"),
            Map.entry("zwnj", "‌"), Map.entry("shy", "­"), Map.entry("iexcl", "¡"),
            Map.entry("iquest", "¿"), Map.entry("brvbar", "¦"), Map.entry("uml", "¨"),
            Map.entry("macr", "¯"), Map.entry("acute", "´"), Map.entry("cedil", "¸"),
            Map.entry("ordf", "ª"), Map.entry("ordm", "º"), Map.entry("curren", "¤"),
            Map.entry("dollar", "$"), Map.entry("percnt", "%"), Map.entry("ast", "*"),
            Map.entry("lowbar", "_"), Map.entry("lsqb", "["), Map.entry("rsqb", "]"),
            Map.entry("lcub", "{"), Map.entry("rcub", "}"), Map.entry("num", "#"),
            Map.entry("commat", "@"), Map.entry("excl", "!"), Map.entry("quest", "?"),
            Map.entry("sol", "/"), Map.entry("bsol", "\\"), Map.entry("verbar", "|"),
            Map.entry("tilde", "˜"), Map.entry("circ", "ˆ"), Map.entry("prime", "′"),
            Map.entry("Prime", "″"), Map.entry("oline", "‾"), Map.entry("frasl", "⁄"));

    private Entities() {}

    /// Where the reference starting at `pos` ends, or -1 if what is there is
    /// just an ampersand.
    static int scan(CharSequence text, int pos) {
        if (pos >= text.length() || text.charAt(pos) != '&') return -1;
        var at = pos + 1;
        if (at < text.length() && text.charAt(at) == '#') {
            at++;
            var hex = at < text.length() && (text.charAt(at) == 'x' || text.charAt(at) == 'X');
            if (hex) at++;
            var digits = at;
            while (at < text.length() && digit(text.charAt(at), hex)) at++;
            if (at == digits || at - digits > 7) return -1;
            return at < text.length() && text.charAt(at) == ';' ? at + 1 : -1;
        }
        var name = at;
        while (at < text.length() && Character.isLetterOrDigit(text.charAt(at))) at++;
        if (at == name || at >= text.length() || text.charAt(at) != ';') return -1;
        return NAMED.containsKey(text.subSequence(name, at).toString()) ? at + 1 : -1;
    }

    /// What a reference means. The argument is the whole thing, ampersand and
    /// semicolon included.
    static String decode(CharSequence reference) {
        var body = reference.subSequence(1, reference.length() - 1).toString();
        if (!body.startsWith("#")) return NAMED.getOrDefault(body, reference.toString());
        var hex = body.length() > 1 && (body.charAt(1) == 'x' || body.charAt(1) == 'X');
        try {
            var value = Integer.parseInt(body.substring(hex ? 2 : 1), hex ? 16 : 10);
            if (value == 0 || value > Character.MAX_CODE_POINT) return "�";
            return new String(Character.toChars(value));
        } catch (NumberFormatException e) {
            return "�";
        }
    }

    private static boolean digit(char character, boolean hex) {
        if (character >= '0' && character <= '9') return true;
        if (!hex) return false;
        var lower = Character.toLowerCase(character);
        return lower >= 'a' && lower <= 'f';
    }
}
