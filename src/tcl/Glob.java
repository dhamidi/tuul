package tcl;

import java.util.regex.Pattern;

final class Glob {

    private Glob() {}

    static boolean matches(String glob, String value, boolean nocase) {
        var regex = new StringBuilder("^");
        var escaped = false;
        for (var at = 0; at < glob.length(); at++) {
            var character = glob.charAt(at);
            if (escaped) {
                literal(regex, character);
                escaped = false;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                continue;
            }
            switch (character) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '[' -> {
                    var close = glob.indexOf(']', at + 1);
                    if (close < 0) literal(regex, character);
                    else {
                        regex.append(glob, at, close + 1);
                        at = close;
                    }
                }
                default -> literal(regex, character);
            }
        }
        if (escaped) literal(regex, '\\');
        regex.append('$');
        return Pattern.compile(regex.toString(), nocase ? Pattern.CASE_INSENSITIVE : 0).matcher(value).matches();
    }

    private static void literal(StringBuilder regex, char character) {
        if (".(){}+^$|\\".indexOf(character) >= 0) regex.append('\\');
        regex.append(character);
    }
}
