package web.ui;

import static web.ui.Attributes.*;
import static web.ui.Tags.*;

import harness.Check;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class UiTest {

    private UiTest() {}

    public static void run() throws IOException {
        escaping();
        rawText();
        elements();
        composition();
        streaming();
        names();
        turbo();
        stimulus();
    }

    /// Text and attributes end at different characters, so they cannot share
    /// one rule.
    private static void escaping() {
        Check.equal("text is escaped where it lands",
                "<p>5 &gt; 3 &amp; 2 &lt; 4</p>",
                p(text("5 > 3 & 2 < 4")).markup());
        Check.equal("a quote in text is left alone, because nothing there ends at one",
                "<p>she said \"hi\"</p>",
                p(text("she said \"hi\"")).markup());
        Check.equal("a quote in an attribute is escaped, because it would end the value",
                "<img alt=\"she said &quot;hi&quot;\">",
                img(alt("she said \"hi\"")).markup());
        Check.equal("so an attribute cannot grow attributes of its own",
                "<img alt=\"&quot; onload=&quot;alert(1)\">",
                img(alt("\" onload=\"alert(1)")).markup());
        Check.equal("an apostrophe is escaped too, for the templates that quote with one",
                "<img alt=\"it&#39;s\">",
                img(alt("it's")).markup());
        Check.equal("an escape is never escaped again",
                "<p>&amp;amp;</p>",
                p(text("&amp;")).markup());
        Check.equal("markup that is already safe goes through untouched",
                "<p><b>bold</b></p>",
                p(unsafe("<b>bold</b>")).markup());
    }

    /// A script holds a program. Escaping it would change the program, so the
    /// only defence is refusing what would end it.
    private static void rawText() {
        Check.equal("a script is written exactly as given",
                "<script>if (a < b && c) go(\"<em>\")</script>",
                script("if (a < b && c) go(\"<em>\")").markup());
        Check.equal("a stylesheet too",
                "<style>a > b { content: \"&\" }</style>",
                style("a > b { content: \"&\" }").markup());
        Check.throwing("a closing script tag inside a script is refused",
                () -> script("var s = \"</script><script>alert(1)\"").markup());
        Check.throwing("whatever its case",
                () -> script("var s = \"</SCRIPT >\"").markup());
        Check.throwing("an opening one too, which sends the parser somewhere else",
                () -> script("var s = \"<script>\"").markup());
        Check.throwing("and a comment open, which does the same",
                () -> script("var s = \"<!--\"").markup());
        Check.throwing("a closing style tag inside a stylesheet is refused",
                () -> style("a { content: \"</style>\" }").markup());
        Check.equal("a script that is only a reference has nothing to refuse",
                "<script src=\"/app.js\" type=\"module\"></script>",
                script(src("/app.js"), type("module")).markup());
    }

    private static void elements() {
        Check.equal("a void element is its whole tag", "<br>", br().markup());
        Check.equal("with its attributes", "<img src=\"/a.png\" alt=\"a\">", img(src("/a.png"), alt("a")).markup());
        Check.throwing("and cannot contain anything",
                () -> Html.element("br", text("nope")).markup());

        Check.equal("a boolean attribute is written bare",
                "<input type=\"checkbox\" checked>",
                input(type("checkbox"), checked()).markup());
        Check.equal("an empty value is not the same as no value",
                "<input value=\"\">",
                input(value("")).markup());
        Check.equal("classes join with spaces",
                "<div class=\"card wide\"></div>",
                div(classes("card", "wide")).markup());
        Check.equal("an empty element still has both tags", "<div></div>", div().markup());
        Check.equal("attributes keep the order they were written in",
                "<a href=\"/x\" class=\"link\" id=\"go\"></a>",
                a(href("/x"), classes("link"), id("go")).markup());
        Check.equal("a document says what it is",
                "<!DOCTYPE html><html lang=\"en\"></html>",
                document(lang("en")).markup());
    }

    private static void composition() {
        Check.equal("elements nest",
                "<div class=\"card\"><h1>Ada</h1><p>hello</p></div>",
                div(classes("card"), h1(text("Ada")), p(text("hello"))).markup());
        Check.equal("attributes and children mix in the order they read best",
                "<div id=\"a\" class=\"b\"><span></span></div>",
                div(id("a"), span(), classes("b")).markup());
        Check.equal("a fragment is several things and no element around them",
                "<li>a</li><li>b</li>",
                fragment(li(text("a")), li(text("b"))).markup());
        Check.equal("nothing renders nothing", "", nothing().markup());
        Check.equal("a component is a method that returns markup",
                "<ul><li>Ada</li><li>Grace</li></ul>",
                ul(each(List.of("Ada", "Grace"), UiTest::item)).markup());
    }

    private static Html item(String name) {
        return li(text(name));
    }

    /// The library's claim is that a page reaches the browser as it is built.
    /// That is only true if a child renders after its parent's opening tag has
    /// already been written, so this asks the writer what it holds at that
    /// moment.
    private static void streaming() throws IOException {
        var out = new StringWriter();
        var whenTheChildRendered = new ArrayList<String>();
        div(id("card"), h1(text("title")), deferred(writer -> whenTheChildRendered.add(out.toString())), p(text("tail")))
                .write(out);

        Check.equal("the opening tag and the earlier children are already written",
                "<div id=\"card\"><h1>title</h1>",
                whenTheChildRendered.getFirst());
        Check.equal("and the rest follows it",
                "<div id=\"card\"><h1>title</h1><p>tail</p></div>",
                out.toString());

        var pulled = new int[1];
        var counted = new ArrayList<>(List.of("a", "b", "c"));
        Iterable<String> items = () -> new Iterator<String>() {
            private final Iterator<String> source = counted.iterator();

            public boolean hasNext() {
                return source.hasNext();
            }

            public String next() {
                pulled[0]++;
                return source.next();
            }
        };
        var list = ul(each(items, UiTest::item));
        Check.equal("nothing is pulled while the markup is only described", 0, pulled[0]);
        Check.equal("and all of it once it is written", "<ul><li>a</li><li>b</li><li>c</li></ul>", list.markup());
        Check.equal("once each", 3, pulled[0]);
    }

    /// A name that is not a name is a way to write an attribute, or a tag, that
    /// nobody asked for.
    private static void names() {
        Check.throwing("an element name is checked", () -> Html.element("div onload=alert(1)"));
        Check.throwing("an attribute name is checked", () -> attribute("x onload=alert(1)", "y"));
        Check.throwing("and an empty one is not a name", () -> attribute("", "y"));
        Check.equal("a custom element is a name", "<turbo-frame></turbo-frame>",
                Html.element("turbo-frame").markup());
        Check.equal("and so is a data attribute", "<div data-x-y=\"1\"></div>", div(data("x-y", "1")).markup());
    }

    private static void turbo() {
        Check.equal("a frame holds markup Turbo can replace",
                "<turbo-frame id=\"messages\"><p>hi</p></turbo-frame>",
                Turbo.frame("messages", p(text("hi"))).markup());
        Check.equal("or fetches its own",
                "<turbo-frame id=\"messages\" src=\"/messages\"></turbo-frame>",
                Turbo.frame("messages", "/messages").markup());
        Check.equal("and can wait until it is seen",
                "<turbo-frame id=\"m\" loading=\"lazy\"></turbo-frame>",
                Turbo.frame("m", Turbo.lazy()).markup());

        Check.equal("a stream wraps its content in the template Turbo reads",
                "<turbo-stream action=\"append\" target=\"messages\"><template><li>new</li></template></turbo-stream>",
                Turbo.append("messages", li(text("new"))).markup());
        Check.equal("update replaces what is inside the target",
                "<turbo-stream action=\"update\" target=\"count\"><template>7</template></turbo-stream>",
                Turbo.update("count", text("7")).markup());
        Check.equal("remove sends nothing, so it carries no template",
                "<turbo-stream action=\"remove\" target=\"flash\"></turbo-stream>",
                Turbo.remove("flash").markup());
        Check.equal("refresh has no target either",
                "<turbo-stream action=\"refresh\"></turbo-stream>",
                Turbo.refresh().markup());
        Check.equal("a stream can aim at every element matching a selector",
                "<turbo-stream action=\"replace\" targets=\".item\"><template><strong>x</strong></template></turbo-stream>",
                Turbo.streamAll(Turbo.Action.REPLACE, ".item", strong(text("x"))).markup());
        Check.equal("a response body is several of them",
                "<turbo-stream action=\"prepend\" target=\"a\"><template>1</template></turbo-stream>"
                        + "<turbo-stream action=\"remove\" target=\"b\"></turbo-stream>",
                Turbo.streams(Turbo.prepend("a", text("1")), Turbo.remove("b")).markup());
        Check.equal("and says what it is", "text/vnd.turbo-stream.html", Turbo.STREAM_TYPE);

        Check.equal("a link can drive another frame",
                "<a data-turbo-frame=\"main\"></a>",
                a(Turbo.targetFrame("main")).markup());
        Check.equal("ask before doing it",
                "<a data-turbo-method=\"delete\" data-turbo-confirm=\"Sure?\"></a>",
                a(Turbo.method("delete"), Turbo.confirm("Sure?")).markup());
        Check.equal("or opt out of Turbo entirely",
                "<div data-turbo=\"false\"></div>",
                div(Turbo.disabled()).markup());
        Check.equal("and keep an element across a navigation",
                "<div id=\"player\" data-turbo-permanent></div>",
                div(id("player"), Turbo.permanent()).markup());
    }

    private static void stimulus() {
        Check.equal("an element says which controllers it is",
                "<div data-controller=\"clipboard modal\"></div>",
                div(Stimulus.controller("clipboard", "modal")).markup());
        Check.equal("a descriptor is event, controller and method",
                "click->clipboard#copy",
                Stimulus.on("click", "clipboard", "copy"));
        Check.equal("actions join with spaces, and the arrow is escaped as an attribute must be",
                "<button data-action=\"click-&gt;clipboard#copy keydown.esc-&gt;modal#close\"></button>",
                button(Stimulus.action(Stimulus.on("click", "clipboard", "copy"),
                        Stimulus.on("keydown.esc", "modal", "close"))).markup());
        Check.equal("a target names what this element is to its controller",
                "<input data-clipboard-target=\"source\">",
                input(Stimulus.target("clipboard", "source")).markup());
        Check.equal("a value carries its own name before the suffix",
                "<div data-clipboard-url-value=\"/x\"></div>",
                div(Stimulus.value("clipboard", "url", "/x")).markup());
        Check.equal("a class keeps its name out of the JavaScript",
                "<div data-clipboard-supported-class=\"bg-green\"></div>",
                div(Stimulus.classes("clipboard", "supported", "bg-green")).markup());
        Check.equal("a param is an argument to the action",
                "<button data-clipboard-id-param=\"3\"></button>",
                button(Stimulus.param("clipboard", "id", "3")).markup());
        Check.equal("an outlet finds another controller by selector",
                "<div data-clipboard-result-outlet=\".result\"></div>",
                div(Stimulus.outlet("clipboard", "result", ".result")).markup());
    }
}
