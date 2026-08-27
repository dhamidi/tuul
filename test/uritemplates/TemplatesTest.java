package uritemplates;

import harness.Check;
import java.io.IOException;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// RFC 6570 comes with its own test suite: the tables in section 3.2 are
/// normative, exhaustive about the awkward cases, and cover every operator
/// against every shape of value. They are reproduced here as written, so a
/// disagreement with them is a disagreement with the spec.
public final class TemplatesTest {

    private TemplatesTest() {}

    /// The variables of RFC 6570 section 3.2, exactly as it defines them.
    private static final Map<String, Object> VARIABLES = variables();

    public static void run() throws IOException {
        variableExpansion();
        simple();
        reserved();
        fragment();
        label();
        path();
        parameter();
        query();
        continuation();
        streaming();
        reading();
        refusing();
        recognising();
    }

    /// Section 3.2.1 — what an operator does to a list.
    private static void variableExpansion() {
        expands("{count}", "one,two,three");
        expands("{count*}", "one,two,three");
        expands("{/count}", "/one,two,three");
        expands("{/count*}", "/one/two/three");
        expands("{;count}", ";count=one,two,three");
        expands("{;count*}", ";count=one;count=two;count=three");
        expands("{?count}", "?count=one,two,three");
        expands("{?count*}", "?count=one&count=two&count=three");
        expands("{&count*}", "&count=one&count=two&count=three");
    }

    /// Section 3.2.2 — simple string expansion.
    private static void simple() {
        expands("{var}", "value");
        expands("{hello}", "Hello%20World%21");
        expands("{half}", "50%25");
        expands("O{empty}X", "OX");
        expands("O{undef}X", "OX");
        expands("{x,y}", "1024,768");
        expands("{x,hello,y}", "1024,Hello%20World%21,768");
        expands("?{x,empty}", "?1024,");
        expands("?{x,undef}", "?1024");
        expands("?{undef,y}", "?768");
        expands("{var:3}", "val");
        expands("{var:30}", "value");
        expands("{list}", "red,green,blue");
        expands("{list*}", "red,green,blue");
        expands("{keys}", "semi,%3B,dot,.,comma,%2C");
        expands("{keys*}", "semi=%3B,dot=.,comma=%2C");
    }

    /// Section 3.2.3 — reserved expansion, where an existing percent-triple
    /// must survive and a bare percent must not.
    private static void reserved() {
        expands("{+var}", "value");
        expands("{+hello}", "Hello%20World!");
        expands("{+half}", "50%25");
        expands("{base}index", "http%3A%2F%2Fexample.com%2Fhome%2Findex");
        expands("{+base}index", "http://example.com/home/index");
        expands("O{+empty}X", "OX");
        expands("O{+undef}X", "OX");
        expands("{+path}/here", "/foo/bar/here");
        expands("here?ref={+path}", "here?ref=/foo/bar");
        expands("up{+path}{var}/here", "up/foo/barvalue/here");
        expands("{+x,hello,y}", "1024,Hello%20World!,768");
        expands("{+path,x}/here", "/foo/bar,1024/here");
        expands("{+path:6}/here", "/foo/b/here");
        expands("{+list}", "red,green,blue");
        expands("{+list*}", "red,green,blue");
        expands("{+keys}", "semi,;,dot,.,comma,,");
        expands("{+keys*}", "semi=;,dot=.,comma=,");
    }

    /// Section 3.2.4 — fragment expansion.
    private static void fragment() {
        expands("{#var}", "#value");
        expands("{#hello}", "#Hello%20World!");
        expands("{#half}", "#50%25");
        expands("foo{#empty}", "foo#");
        expands("foo{#undef}", "foo");
        expands("{#x,hello,y}", "#1024,Hello%20World!,768");
        expands("{#path,x}/here", "#/foo/bar,1024/here");
        expands("{#path:6}/here", "#/foo/b/here");
        expands("{#list}", "#red,green,blue");
        expands("{#list*}", "#red,green,blue");
        expands("{#keys}", "#semi,;,dot,.,comma,,");
        expands("{#keys*}", "#semi=;,dot=.,comma=,");
    }

    /// Section 3.2.5 — label expansion.
    private static void label() {
        expands("{.who}", ".fred");
        expands("{.who,who}", ".fred.fred");
        expands("{.half,who}", ".50%25.fred");
        expands("www{.dom*}", "www.example.com");
        expands("X{.var}", "X.value");
        expands("X{.empty}", "X.");
        expands("X{.undef}", "X");
        expands("X{.var:3}", "X.val");
        expands("X{.list}", "X.red,green,blue");
        expands("X{.list*}", "X.red.green.blue");
        expands("X{.keys}", "X.semi,%3B,dot,.,comma,%2C");
        expands("X{.keys*}", "X.semi=%3B.dot=..comma=%2C");
        expands("X{.empty_keys}", "X");
        expands("X{.empty_keys*}", "X");
    }

    /// Section 3.2.6 — path segments.
    private static void path() {
        expands("{/who}", "/fred");
        expands("{/who,who}", "/fred/fred");
        expands("{/half,who}", "/50%25/fred");
        expands("{/who,dub}", "/fred/me%2Ftoo");
        expands("{/var}", "/value");
        expands("{/var,empty}", "/value/");
        expands("{/var,undef}", "/value");
        expands("{/var,x}/here", "/value/1024/here");
        expands("{/var:1,var}", "/v/value");
        expands("{/list}", "/red,green,blue");
        expands("{/list*}", "/red/green/blue");
        expands("{/list*,path:4}", "/red/green/blue/%2Ffoo");
        expands("{/keys}", "/semi,%3B,dot,.,comma,%2C");
        expands("{/keys*}", "/semi=%3B/dot=./comma=%2C");
    }

    /// Section 3.2.7 — path-style parameters, where an empty value loses its
    /// equals sign.
    private static void parameter() {
        expands("{;who}", ";who=fred");
        expands("{;half}", ";half=50%25");
        expands("{;empty}", ";empty");
        expands("{;v,empty,who}", ";v=6;empty;who=fred");
        expands("{;v,bar,who}", ";v=6;who=fred");
        expands("{;x,y}", ";x=1024;y=768");
        expands("{;x,y,empty}", ";x=1024;y=768;empty");
        expands("{;x,y,undef}", ";x=1024;y=768");
        expands("{;hello:5}", ";hello=Hello");
        expands("{;list}", ";list=red,green,blue");
        expands("{;list*}", ";list=red;list=green;list=blue");
        expands("{;keys}", ";keys=semi,%3B,dot,.,comma,%2C");
        expands("{;keys*}", ";semi=%3B;dot=.;comma=%2C");
    }

    /// Section 3.2.8 — form-style queries, where an empty value keeps it.
    private static void query() {
        expands("{?who}", "?who=fred");
        expands("{?half}", "?half=50%25");
        expands("{?x,y}", "?x=1024&y=768");
        expands("{?x,y,empty}", "?x=1024&y=768&empty=");
        expands("{?x,y,undef}", "?x=1024&y=768");
        expands("{?var:3}", "?var=val");
        expands("{?list}", "?list=red,green,blue");
        expands("{?list*}", "?list=red&list=green&list=blue");
        expands("{?keys}", "?keys=semi,%3B,dot,.,comma,%2C");
        expands("{?keys*}", "?semi=%3B&dot=.&comma=%2C");
    }

    /// Section 3.2.9 — continuing a query somebody else began.
    private static void continuation() {
        expands("{&who}", "&who=fred");
        expands("{&half}", "&half=50%25");
        expands("?fixed=yes{&x}", "?fixed=yes&x=1024");
        expands("{&x,y,empty}", "&x=1024&y=768&empty=");
        expands("{&x,y,undef}", "&x=1024&y=768");
        expands("{&var:3}", "&var=val");
        expands("{&list}", "&list=red,green,blue");
        expands("{&list*}", "&list=red&list=green&list=blue");
        expands("{&keys}", "&keys=semi,%3B,dot,.,comma,%2C");
        expands("{&keys*}", "&semi=%3B&dot=.&comma=%2C");
    }

    private static void streaming() throws IOException {
        var out = new StringWriter();
        Template.of("/users/{id}/posts{?tag}").expand(Map.of("id", "42", "tag", "java"), out);
        Check.equal("a template expands into a writer as it goes", "/users/42/posts?tag=java", out.toString());

        var template = Template.of("{/a,b}");
        Check.equal("the same template expands again", "/1/2", template.expand(Map.of("a", "1", "b", "2")));
        Check.equal("and again, with other values", "/3/4", template.expand(Map.of("a", "3", "b", "4")));
    }

    private static void reading() {
        var template = Template.of("/users/{id}/posts{?tag,page}");
        Check.equal("a template remembers how it was written", "/users/{id}/posts{?tag,page}", template.text());
        Check.equal("and which variables it mentions", List.of("id", "tag", "page"), template.names());
        Check.equal("a name is listed once however often it is used",
                List.of("who"), Template.of("{who}{.who}{/who}").names());
        Check.equal("a template is text, a literal is not a variable",
                List.of(), Template.of("/just/a/path").names());
        Check.equal("dots are part of a variable name, not a path through one",
                List.of("a.b"), Template.of("{a.b}").names());
        Check.equal("and a name may be percent-encoded",
                "x", Template.of("{%78}").expand(Map.of("%78", "x")));
        Check.equal("unicode is encoded by its bytes, not its characters",
                "%E2%82%AC", Template.of("{money}").expand(Map.of("money", "€")));
        Check.equal("a prefix counts characters, and takes them before encoding",
                "%E2%82%AC%E2%82%AC", Template.of("{money:2}").expand(Map.of("money", "€€€")));
        Check.equal("a value that is not a string is asked what it says",
                "/42/true", Template.of("{/n,flag}").expand(Map.of("n", 42, "flag", true)));
    }

    private static void refusing() {
        Check.throwing("a brace that never closes is not a template", () -> Template.of("{unclosed"));
        Check.throwing("nor is a closing brace on its own", () -> Template.of("closed}"));
        Check.throwing("nor an expression with no variable in it", () -> Template.of("{}"));
        Check.throwing("an operator reserved for a future RFC is refused now",
                () -> Template.of("{=var}"));
        Check.throwing("so are the other four", () -> Template.of("{|var}"));
        Check.throwing("a prefix has to be a number", () -> Template.of("{var:}"));
        Check.throwing("and a positive one", () -> Template.of("{var:0}"));
        Check.throwing("and a small one", () -> Template.of("{var:10000}"));
        Check.throwing("a bare percent is not literal text — a URI would read it as a triple",
                () -> Template.of("50% off"));
        Check.throwing("nor is a space", () -> Template.of("a b"));
        Check.throwing("a variable cannot have both modifiers", () -> Template.of("{hello:2*}"));
        Check.throwing("nor a modifier the RFC never gave it", () -> Template.of("{var|3}"));
        Check.throwing("an expression cannot hold an assignment", () -> Template.of("{?empty=default,var}"));
        Check.throwing("a variable name is narrower than a URI is", () -> Template.of("{example:color?}"));
        Check.equal("a template with nothing in it expands to nothing", "", Template.of("").expand(Map.of()));
        Check.equal("a template with no expressions is its own expansion",
                "/just/a/path", Template.of("/just/a/path").expand(VARIABLES));
        Check.equal("a percent-triple in the literal text stands as written, encoded",
                "50%25%20off", Template.of("50%25%20off").expand(Map.of()));

        Check.throwing("a prefix of a list has no meaning, so it is not guessed at",
                () -> Template.of("{list:3}").expand(VARIABLES));
        Check.throwing("nor a prefix of a map", () -> Template.of("{keys:3}").expand(VARIABLES));
    }

    /// The direction the RFC does not specify, offered where it is not a guess.
    private static void recognising() {
        var route = Template.of("/users/{id}/posts/{slug}");
        Check.that("a path template can be read backwards", route.matchable());
        Check.equal("and gives back what would have made the URL",
                Map.of("id", "42", "slug", "hello"),
                route.match("/users/42/posts/hello").orElseThrow());
        Check.that("a URL it could not have made does not match",
                route.match("/accounts/42/posts/hello").isEmpty());
        Check.that("nor one that stops early", route.match("/users/42/posts").isEmpty());
        Check.equal("a value comes back decoded",
                Map.of("name", "me/too"),
                Template.of("/files/{name}").match("/files/me%2Ftoo").orElseThrow());
        Check.equal("a separator inside a value cannot be mistaken for a separator",
                Map.of("a", "x/y", "b", "z"),
                Template.of("{/a,b}").match("/x%2Fy/z").orElseThrow());
        Check.equal("what a route expands to is what it recognises",
                Map.of("id", "42"),
                route(Template.of("/users/{id}"), Map.of("id", "42")));

        Check.that("a query cannot be read backwards: its order is not ours to assume",
                !Template.of("/search{?q}").matchable());
        Check.that("a reserved expansion cannot: a value may contain what would end it",
                !Template.of("/proxy/{+url}").matchable());
        Check.that("a label cannot: a dot survives encoding, so the dots are ambiguous",
                !Template.of("X{.name}").matchable());
        Check.that("a prefix cannot: the rest of the value is gone",
                !Template.of("/users/{id:2}").matchable());
        Check.that("an explode cannot, yet", !Template.of("{/path*}").matchable());
        Check.that("and a template that cannot be read backwards never matches",
                Template.of("/search{?q}").match("/search?q=x").isEmpty());
    }

    /// Expands `template` against the RFC's variables and checks the row.
    private static void expands(String template, String expected) {
        Check.equal(template + "  →  " + expected, expected, Template.of(template).expand(VARIABLES));
    }

    /// A round trip: expand, then recognise, and see the values come home.
    private static Map<String, String> route(Template template, Map<String, String> values) {
        return template.match(template.expand(values)).orElseThrow();
    }

    private static Map<String, Object> variables() {
        var keys = new LinkedHashMap<String, String>();
        keys.put("semi", ";");
        keys.put("dot", ".");
        keys.put("comma", ",");

        var variables = new LinkedHashMap<String, Object>();
        variables.put("count", List.of("one", "two", "three"));
        variables.put("dom", List.of("example", "com"));
        variables.put("dub", "me/too");
        variables.put("hello", "Hello World!");
        variables.put("half", "50%");
        variables.put("var", "value");
        variables.put("who", "fred");
        variables.put("base", "http://example.com/home/");
        variables.put("path", "/foo/bar");
        variables.put("list", List.of("red", "green", "blue"));
        variables.put("keys", keys);
        variables.put("v", "6");
        variables.put("x", "1024");
        variables.put("y", "768");
        variables.put("empty", "");
        variables.put("empty_keys", Map.of());
        variables.put("undef", null);
        return variables;
    }
}
