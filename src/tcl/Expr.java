package tcl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

final class Expr {

    private final Tcl tcl;
    private final String source;
    private int at;
    private Token token;

    Expr(Tcl tcl, String source) {
        this.tcl = tcl;
        this.source = source;
        token = next();
    }

    Object parse() {
        var expression = conditional();
        if (token.kind != Kind.END) throw error("unexpected token \"" + token.text + "\" in expression");
        return expression.get();
    }

    private Node conditional() {
        var condition = or();
        if (!take("?")) return condition;
        var yes = conditional();
        require(":");
        var no = conditional();
        return () -> Values.bool(condition.get()) ? yes.get() : no.get();
    }

    private Node or() {
        var left = and();
        while (take("||")) {
            var first = left;
            var right = and();
            left = () -> Values.bool(first.get()) || Values.bool(right.get());
        }
        return left;
    }

    private Node and() {
        var left = bitOr();
        while (take("&&")) {
            var first = left;
            var right = bitOr();
            left = () -> Values.bool(first.get()) && Values.bool(right.get());
        }
        return left;
    }

    private Node bitOr() {
        var left = bitXor();
        while (take("|")) left = binary(left, bitXor(), "|");
        return left;
    }

    private Node bitXor() {
        var left = bitAnd();
        while (take("^")) left = binary(left, bitAnd(), "^");
        return left;
    }

    private Node bitAnd() {
        var left = equality();
        while (take("&")) left = binary(left, equality(), "&");
        return left;
    }

    private Node equality() {
        var left = relational();
        while (is("==") || is("!=") || is("eq") || is("ne")) {
            var operator = token.text;
            advance();
            left = binary(left, relational(), operator);
        }
        return left;
    }

    private Node relational() {
        var left = shift();
        while (is("<") || is(">") || is("<=") || is(">=")) {
            var operator = token.text;
            advance();
            left = binary(left, shift(), operator);
        }
        return left;
    }

    private Node shift() {
        var left = additive();
        while (is("<<") || is(">>")) {
            var operator = token.text;
            advance();
            left = binary(left, additive(), operator);
        }
        return left;
    }

    private Node additive() {
        var left = multiply();
        while (is("+") || is("-")) {
            var operator = token.text;
            advance();
            left = binary(left, multiply(), operator);
        }
        return left;
    }

    private Node multiply() {
        var left = power();
        while (is("*") || is("/") || is("%")) {
            var operator = token.text;
            advance();
            left = binary(left, power(), operator);
        }
        return left;
    }

    private Node power() {
        var left = unary();
        if (!take("**")) return left;
        return binary(left, power(), "**");
    }

    private Node unary() {
        if (is("+") || is("-") || is("!") || is("~")) {
            var operator = token.text;
            advance();
            var value = unary();
            return () -> unary(operator, value.get());
        }
        return primary();
    }

    private Node primary() {
        if (take("(")) {
            var result = conditional();
            require(")");
            return result;
        }
        if (token.kind == Kind.VARIABLE) {
            var name = token.text;
            advance();
            return () -> tcl.expressionVariable(name);
        }
        if (token.kind == Kind.SCRIPT) {
            var script = token.text;
            advance();
            return () -> tcl.expressionScript(script);
        }
        if (token.kind == Kind.QUOTED) {
            var source = token.text;
            advance();
            return () -> tcl.expressionQuoted(source);
        }
        if (token.kind == Kind.WORD) {
            var word = token.text;
            advance();
            if (take("(")) {
                var arguments = new ArrayList<Node>();
                if (!is(")")) {
                    do arguments.add(conditional()); while (take(","));
                }
                require(")");
                return () -> function(word, arguments.stream().map(Node::get).toList());
            }
            var value = literal(word);
            return () -> value;
        }
        throw error("missing operand in expression");
    }

    private Node binary(Node left, Node right, String operator) {
        return () -> binary(operator, left.get(), right.get());
    }

    private Object binary(String operator, Object left, Object right) {
        return switch (operator) {
            case "+" -> arithmetic(left, right, Math::addExact, (a, b) -> a + b);
            case "-" -> arithmetic(left, right, Math::subtractExact, (a, b) -> a - b);
            case "*" -> arithmetic(left, right, Math::multiplyExact, (a, b) -> a * b);
            case "/" -> divide(left, right);
            case "%" -> Values.integer(left) % nonzero(Values.integer(right));
            case "**" -> power(left, right);
            case "<<" -> Values.integer(left) << Values.integer(right);
            case ">>" -> Values.integer(left) >> Values.integer(right);
            case "&" -> Values.integer(left) & Values.integer(right);
            case "^" -> Values.integer(left) ^ Values.integer(right);
            case "|" -> Values.integer(left) | Values.integer(right);
            case "==" -> equal(left, right);
            case "!=" -> !equal(left, right);
            case "eq" -> Values.string(left).equals(Values.string(right));
            case "ne" -> !Values.string(left).equals(Values.string(right));
            case "<" -> compare(left, right) < 0;
            case ">" -> compare(left, right) > 0;
            case "<=" -> compare(left, right) <= 0;
            case ">=" -> compare(left, right) >= 0;
            default -> throw error("unknown operator \"" + operator + "\"");
        };
    }

    private Object unary(String operator, Object value) {
        return switch (operator) {
            case "+" -> numeric(value);
            case "-" -> negate(value);
            case "!" -> !Values.bool(value);
            case "~" -> ~Values.integer(value);
            default -> throw error("unknown unary operator");
        };
    }

    private Object function(String name, List<Object> args) {
        return switch (name) {
            case "int" -> {
                count(name, args, 1, 1);
                yield Values.integer(args.getFirst());
            }
            case "double" -> {
                count(name, args, 1, 1);
                yield Values.number(args.getFirst());
            }
            case "abs" -> {
                count(name, args, 1, 1);
                var value = args.getFirst();
                if (integral(value)) {
                    var integer = Values.integer(value);
                    if (integer == Long.MIN_VALUE) throw arithmetic("integer value too large", "OVERFLOW");
                    yield Math.abs(integer);
                }
                yield Math.abs(Values.number(value));
            }
            case "round" -> {
                count(name, args, 1, 1);
                yield Math.round(Values.number(args.getFirst()));
            }
            case "min" -> {
                count(name, args, 1, Integer.MAX_VALUE);
                var result = args.getFirst();
                for (var value : args.subList(1, args.size())) if (compare(value, result) < 0) result = value;
                yield result;
            }
            case "max" -> {
                count(name, args, 1, Integer.MAX_VALUE);
                var result = args.getFirst();
                for (var value : args.subList(1, args.size())) if (compare(value, result) > 0) result = value;
                yield result;
            }
            default -> throw error("unknown math function \"" + name + "\"");
        };
    }

    private static Object arithmetic(Object left, Object right, LongOperation integers, DoubleOperation doubles) {
        if (integral(left) && integral(right)) {
            try {
                return integers.apply(Values.integer(left), Values.integer(right));
            } catch (ArithmeticException e) {
                throw arithmetic("integer value too large", "OVERFLOW");
            }
        }
        return doubles.apply(Values.number(left), Values.number(right));
    }

    private static Object divide(Object left, Object right) {
        if (integral(left) && integral(right)) {
            var dividend = Values.integer(left);
            var divisor = nonzero(Values.integer(right));
            if (dividend == Long.MIN_VALUE && divisor == -1) throw arithmetic("integer value too large", "OVERFLOW");
            return dividend / divisor;
        }
        var divisor = Values.number(right);
        if (divisor == 0.0) throw arithmetic("divide by zero", "DIVZERO");
        return Values.number(left) / divisor;
    }

    private static long nonzero(long value) {
        if (value == 0) throw arithmetic("divide by zero", "DIVZERO");
        return value;
    }

    private static Object power(Object left, Object right) {
        if (integral(left) && integral(right) && Values.integer(right) >= 0) {
            var base = Values.integer(left);
            var exponent = Values.integer(right);
            long result = 1;
            try {
                while (exponent-- > 0) result = Math.multiplyExact(result, base);
            } catch (ArithmeticException e) {
                throw arithmetic("integer value too large", "OVERFLOW");
            }
            return result;
        }
        return Math.pow(Values.number(left), Values.number(right));
    }

    private static Object numeric(Object value) {
        if (integral(value)) return Values.integer(value);
        return Values.number(value);
    }

    private static Object negate(Object value) {
        if (integral(value)) {
            try {
                return Math.negateExact(Values.integer(value));
            } catch (ArithmeticException e) {
                throw arithmetic("integer value too large", "OVERFLOW");
            }
        }
        return -Values.number(value);
    }

    private static boolean integral(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return true;
        if (!(value instanceof String text)) return false;
        try {
            Long.decode(text.strip());
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean equal(Object left, Object right) {
        if (numericLike(left) && numericLike(right)) return Double.compare(Values.number(left), Values.number(right)) == 0;
        return Objects.equals(left, right);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compare(Object left, Object right) {
        if (numericLike(left) && numericLike(right)) return Double.compare(Values.number(left), Values.number(right));
        if (left instanceof Comparable comparable && left.getClass().isInstance(right)) return comparable.compareTo(right);
        throw error("values are not comparable");
    }

    private static boolean numericLike(Object value) {
        if (value instanceof Number) return true;
        if (!(value instanceof String text)) return false;
        try {
            Double.parseDouble(text.strip());
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static Object literal(String word) {
        return switch (word.toLowerCase()) {
            case "true", "yes", "on" -> true;
            case "false", "no", "off" -> false;
            default -> {
                try {
                    yield Long.decode(word);
                } catch (NumberFormatException ignored) {
                    try {
                        yield Double.parseDouble(word);
                    } catch (NumberFormatException alsoIgnored) {
                        yield word;
                    }
                }
            }
        };
    }

    private Token next() {
        while (at < source.length() && Character.isWhitespace(source.charAt(at))) at++;
        if (at == source.length()) return new Token(Kind.END, "");
        var start = at;
        var character = source.charAt(at++);
        if (character == '$') return new Token(Kind.VARIABLE, variable());
        if (character == '[') return new Token(Kind.SCRIPT, script());
        if (character == '"') return new Token(Kind.QUOTED, quoted());
        for (var operator : List.of("**", "<<", ">>", "<=", ">=", "==", "!=", "&&", "||")) {
            if (source.startsWith(operator, start)) {
                at = start + operator.length();
                return new Token(Kind.OPERATOR, operator);
            }
        }
        if ("+-*/%<>&^|!~()?:,".indexOf(character) >= 0) return new Token(Kind.OPERATOR, Character.toString(character));
        while (at < source.length() && !Character.isWhitespace(source.charAt(at))
                && "+-*/%<>&^|!~()?:,".indexOf(source.charAt(at)) < 0) at++;
        return new Token(Kind.WORD, source.substring(start, at));
    }

    private String variable() {
        if (at < source.length() && source.charAt(at) == '{') {
            var close = source.indexOf('}', ++at);
            if (close < 0) throw error("unclosed variable name in expression");
            var name = source.substring(at, close);
            at = close + 1;
            return name;
        }
        var start = at;
        while (at < source.length() && (Character.isLetterOrDigit(source.charAt(at))
                || source.charAt(at) == '_' || source.charAt(at) == ':')) at++;
        if (at < source.length() && source.charAt(at) == '(') {
            var close = source.indexOf(')', at + 1);
            if (close < 0) throw error("unclosed array index in expression");
            at = close + 1;
        }
        return source.substring(start, at);
    }

    private String script() {
        var start = at;
        var depth = 1;
        var braces = 0;
        var quote = false;
        while (at < source.length()) {
            var character = source.charAt(at++);
            if (character == '\\') {
                if (at < source.length()) at++;
                continue;
            }
            if (braces > 0) {
                if (character == '{') braces++;
                else if (character == '}') braces--;
                continue;
            }
            if (!quote && character == '{') braces++;
            else if (character == '"') quote = !quote;
            else if (!quote && character == '[') depth++;
            else if (!quote && character == ']' && --depth == 0) return source.substring(start, at - 1);
        }
        throw error("unclosed command substitution in expression");
    }

    private String quoted() {
        var start = at;
        var result = new StringBuilder();
        while (at < source.length() && source.charAt(at) != '"') {
            var character = source.charAt(at++);
            if (character == '\\' && at < source.length()) {
                result.append(character);
                character = source.charAt(at++);
            }
            result.append(character);
        }
        if (at == source.length()) throw error("unclosed quote in expression");
        at++;
        return result.toString();
    }

    private boolean is(String text) {
        return token.text.equals(text);
    }

    private boolean take(String text) {
        if (!is(text)) return false;
        advance();
        return true;
    }

    private void require(String text) {
        if (!take(text)) throw error("expected \"" + text + "\" in expression");
    }

    private void advance() {
        token = next();
    }

    private static void count(String name, List<?> args, int minimum, int maximum) {
        if (args.size() < minimum || args.size() > maximum) throw error("wrong # args for math function \"" + name + "\"");
    }

    private static TclException.Error arithmetic(String message, String kind) {
        return new TclException.Error(message, List.of("ARITH", kind, message));
    }

    private static TclException.Error error(String message) {
        return new TclException.Error(message);
    }

    @FunctionalInterface private interface Node extends Supplier<Object> {}
    @FunctionalInterface private interface LongOperation { long apply(long left, long right); }
    @FunctionalInterface private interface DoubleOperation { double apply(double left, double right); }
    private enum Kind { WORD, QUOTED, VARIABLE, SCRIPT, OPERATOR, END }
    private record Token(Kind kind, String text) {}
}
