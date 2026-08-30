package web.forms;

import harness.Check;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import json.Json;
import jsonschema.Store;
import web.Headers;
import web.IntegerParameter;
import web.Parameter;
import web.Parameters;
import web.Request;
import web.RouteRef;
import web.Router;
import web.StringParameter;

/// A form is one definition doing three jobs, and these tests are mostly about
/// the third: that what somebody typed survives a failed submission and comes
/// back on the screen. A framework that loses a half-filled form is one people
/// work around, so the round trip is asserted on the rendered markup rather
/// than on the values behind it.
public final class FormsTest {

    private FormsTest() {}

    public static void run() throws IOException {
        capturing();
        answering();
        coercing();
        typedParameters();
        routeActions();
        schemas();
        repeating();
        checkboxes();
        allowlist();
        rules();
        together();
        roundTrip();
        rendering();
        editing();
        messages();
        naming();
        defining();
    }

    /// A form built the way an application would build one.
    private static Form form() {
        return Form.at("/symbols").post()
                .with(Field.text("name").required().label("Symbol").hint("A fully qualified type name"),
                        Field.number("limit").label("Results"),
                        Field.checkbox("all").label("Include private members"),
                        Field.choice("kind", "class", "interface", "record"),
                        Field.textarea("note"));
    }

    /// Parameters as a browser sends them: a name may repeat, and the order it
    /// repeats in is the order it arrived in.
    private static Parameters submitted(String... pairs) {
        var values = new LinkedHashMap<String, List<String>>();
        for (var index = 0; index < pairs.length; index += 2) {
            values.computeIfAbsent(pairs[index], ignored -> new ArrayList<>()).add(pairs[index + 1]);
        }
        return new Parameters(values);
    }

    private static void capturing() {
        var captured = form().capture(submitted("name", "json.Json", "limit", "20", "kind", "record"));
        Check.that("a submission with everything right is ok", captured.ok());
        Check.equal("text arrives as text", "json.Json", captured.text("name", ""));
        Check.equal("a number arrives as a number", Json.of(20), captured.values().get("limit"));
        Check.equal("and the whole thing is one JSON object an update can read",
                "{\"name\":\"json.Json\",\"limit\":20,\"all\":false,\"kind\":\"record\"}",
                captured.values().text());
    }

    /// Absent, blank and answered are three different things, and a form that
    /// treats them as two gets one of them wrong.
    private static void answering() {
        var form = Form.at("/x").with(Field.text("title").required(), Field.text("note"), Field.number("count"));

        var nothing = form.capture(Parameters.NONE);
        Check.equal("a required field that was never submitted is required", List.of("is required"),
                nothing.problems("title"));
        Check.that("and an optional one that was never submitted captures nothing",
                nothing.values().get("note") == null);
        Check.that("a form with nothing in it has not been answered", !nothing.answered());

        var blank = form.capture(submitted("title", "   ", "note", "", "count", ""));
        Check.equal("whitespace is not an answer to a required field", List.of("is required"),
                blank.problems("title"));
        Check.equal("an optional text field that was cleared keeps the clearing", Json.of(""),
                blank.values().get("note"));
        Check.that("an empty number is nothing rather than zero", blank.values().get("count") == null);
        Check.that("and an empty number is not a coercion failure", blank.problems("count").isEmpty());
        Check.that("a form with anything in it has been answered", blank.answered());
    }

    private static void coercing() {
        var form = Form.at("/x").with(Field.number("count"), Field.decimal("weight"), Field.date("due"));

        var wrong = form.capture(submitted("count", "seven", "weight", "heavy", "due", "yesterday"));
        Check.equal("a number that is not one says so", List.of("must be a whole number"), wrong.problems("count"));
        Check.equal("so does a decimal", List.of("must be a number"), wrong.problems("weight"));
        Check.equal("so does a date", List.of("must be a date, as YYYY-MM-DD"), wrong.problems("due"));
        Check.that("and nothing is captured for a value nobody could read",
                wrong.values().get("count") == null);

        var right = form.capture(submitted("count", " 42 ", "weight", "1.5", "due", "2026-08-28"));
        Check.that("the right ones are read: " + right.problems(), right.ok());
        Check.equal("surrounding space is not part of a number", Json.of(42), right.values().get("count"));
        Check.equal("a decimal keeps its fraction", Json.of(1.5), right.values().get("weight"));
        Check.equal("a date is normalised to the way it is written down", Json.of("2026-08-28"),
                right.values().get("due"));
    }

    private static void typedParameters() {
        var count = new IntegerParameter("count");
        Parameter<Code> code = new CodeParameter("code");
        var form = Form.at("/x").with(Field.of(count), Field.of(code));

        var captured = form.capture(submitted("count", "7", "code", "ABC-12"));
        Check.equal("a standard parameter gives a form its typed value", 7,
                captured.value(count).orElseThrow());
        Check.equal("an application parameter does the same", new Code("ABC-12"),
                captured.value(code).orElseThrow());
        Check.equal("its default JSON shape is its formatted text", Json.of("ABC-12"),
                captured.values().get("code"));

        var wrong = form.capture(submitted("count", "seven", "code", "lower"));
        Check.equal("a standard parser supplies its form problem", List.of("must be a whole number"),
                wrong.problems("count"));
        Check.equal("an application parser supplies its form problem", List.of("must be an uppercase code"),
                wrong.problems("code"));

        var repeated = Form.at("/x").with(Field.of(code).repeated())
                .capture(submitted("code", "ABC-1", "code", "XYZ-2"));
        Check.equal("a repeated typed field keeps every Java value",
                List.of(new Code("ABC-1"), new Code("XYZ-2")), repeated.values(code));
    }

    private record Code(String value) {}

    private record CodeParameter(String name) implements Parameter<Code> {
        @Override
        public Code parse(String text) {
            if (!text.matches("[A-Z]+-[0-9]+")) throw new IllegalArgumentException();
            return new Code(text);
        }

        @Override
        public String format(Code code) {
            return code.value();
        }

        @Override
        public String invalid() {
            return "must be an uppercase code";
        }
    }

    private static void routeActions() {
        var id = new IntegerParameter("id");
        var edit = RouteRef.of("edit", "/posts/{id}/edit", id);
        var mounted = Router.of().mount("/admin", Router.of().get(edit));
        Check.equal("a form action follows a mounted route reference", "/admin/posts/7/edit",
                Form.at(mounted, edit.with(id, 7)).action());
    }

    private static void schemas() {
        var properties = Json.Object.of()
                .with("name", Json.Object.of().with("type", "string"))
                .with("age", Json.Object.of().with("type", "integer").with("minimum", 18));
        var schema = Store.of().compile(Json.Object.of()
                .with("type", "object")
                .with("properties", properties)
                .with("required", Json.Array.strings(List.of("name"))));
        var form = Form.at("/people")
                .with(Field.text("name"), Field.integer("age"))
                .schema(schema);

        var rejected = form.capture(submitted("age", "16"));
        Check.equal("a schema property error appears beside its field", List.of("must be >= 18"),
                rejected.problems("age"));
        Check.equal("a schema required error appears beside the missing field",
                List.of("must have required property 'name'"),
                rejected.problems("name"));
        Check.that("schema errors reject the form", !rejected.ok());

        Check.that("a schema accepts parsed form JSON",
                form.capture(submitted("name", "Ada", "age", "37")).ok());
    }

    private static void repeating() {
        var form = Form.at("/x")
                .with(Field.choice("tags", "java", "web", "sql").repeated(), Field.text("one"));

        var many = form.capture(submitted("tags", "java", "tags", "sql", "one", "first", "one", "second"));
        Check.equal("a repeated field keeps every value, in order",
                "[\"java\",\"sql\"]", many.values().get("tags").text());
        Check.equal("a field that is not repeated keeps the first", Json.of("first"), many.values().get("one"));
        Check.equal("and the rest are still there to be rendered back", List.of("first", "second"), many.all("one"));

        var single = form.capture(submitted("tags", "java"));
        Check.equal("a repeated field is a list even when one value arrived",
                "[\"java\"]", single.values().get("tags").text());
    }

    /// The one control a browser does not send when the answer is no.
    private static void checkboxes() {
        var form = Form.at("/x").with(Field.checkbox("all"), Field.checkbox("terms").required());

        var missing = form.capture(submitted("terms", "1"));
        Check.equal("a checkbox nobody ticked is false, not missing", Json.of(false),
                missing.values().get("all"));
        Check.that("and that is not a problem", missing.problems("all").isEmpty());

        var hidden = form.capture(submitted("all", "0", "terms", "1"));
        Check.that("the hidden input alone means no", !hidden.ticked("all"));

        var both = form.capture(submitted("all", "0", "all", "1", "terms", "1"));
        Check.that("the hidden input and the box means yes, because the box is written second",
                both.ticked("all"));

        var unticked = form.capture(submitted("all", "1"));
        Check.equal("a required checkbox that was not ticked says what it wants",
                List.of("must be checked"), unticked.problems("terms"));
    }

    /// The definition is the allowlist, which is the whole of what mass
    /// assignment protection has to be.
    private static void allowlist() {
        var captured = form().capture(submitted("name", "x", "admin", "true", "id", "7"));
        Check.that("a parameter nobody asked for is not captured", captured.values().get("admin") == null);
        Check.equal("but it is not silently gone either", List.of("admin", "id"), captured.ignored());
        Check.that("_method is not reported, since a form puts it there itself",
                !form().capture(submitted("name", "x", "_method", "patch")).ignored().contains("_method"));
    }

    private static void rules() {
        var form = Form.at("/x").with(
                Field.text("title").rule(Rules.least(3)).rule(Rules.most(8)),
                Field.email("email"),
                Field.number("age").rule(Rules.between(18, 120)),
                Field.text("kind").rule(Rules.oneOf("class", "record")));

        var wrong = form.capture(submitted("title", "ab", "email", "nope", "age", "9", "kind", "enum"));
        Check.equal("too short", List.of("must be at least 3 characters"), wrong.problems("title"));
        Check.equal("not an address", List.of("must be an email address"), wrong.problems("email"));
        Check.equal("out of range", List.of("must be between 18 and 120"), wrong.problems("age"));
        Check.equal("not one of them", List.of("is not one of class, record"), wrong.problems("kind"));

        var right = form.capture(submitted("title", "hello", "email", "a@b.co", "age", "40", "kind", "record"));
        Check.that("and none of them fire when the values are fine: " + right.problems(), right.ok());

        var blank = Form.at("/x").with(Field.text("title").rule(Rules.least(3))).capture(Parameters.NONE);
        Check.that("a rule says nothing about a field that was not answered", blank.ok());

        var one = form.capture(submitted("title", "ab", "email", "a@b.co", "age", "40", "kind", "record"));
        Check.equal("the first rule to fail is the one reported, not all of them",
                1, one.problems("title").size());
    }

    private static void together() {
        var form = Form.at("/x")
                .with(Field.password("password").required(), Field.password("confirm").required())
                .check("confirm", "must match the password",
                        values -> values.string("password", "").equals(values.string("confirm", "")));

        var mismatched = form.capture(submitted("password", "secret", "confirm", "different"));
        Check.equal("a check about two fields is reported beside the second",
                List.of("must match the password"), mismatched.problems("confirm"));

        var matched = form.capture(submitted("password", "secret", "confirm", "secret"));
        Check.that("and says nothing when they agree", matched.ok());

        var missing = form.capture(submitted("password", "secret"));
        Check.equal("it is not asked at all while a field is still missing, since saying they do not"
                        + " match would not be help",
                List.of("is required"), missing.problems("confirm"));

        var general = Form.at("/x").with(Field.date("from"), Field.date("to"))
                .check("", "the dates are the wrong way round",
                        values -> values.string("from", "").compareTo(values.string("to", "")) <= 0)
                .capture(submitted("from", "2026-09-01", "to", "2026-08-01"));
        Check.equal("a check that belongs to no field belongs to the form",
                List.of("the dates are the wrong way round"), general.general());
    }

    /// The whole reason this package exists.
    private static void roundTrip() {
        var submitted = submitted("name", "  ", "limit", "seven", "kind", "record", "note", "a <b>note</b>");
        var rejected = form().capture(submitted);
        var markup = Forms.html(rejected).markup();

        Check.that("a failed submission is not ok", !rejected.ok());
        Check.that("the number that could not be read is still on the screen, as it was typed",
                markup.contains("value=\"seven\""));
        Check.that("the whitespace somebody typed is still there", markup.contains("value=\"  \""));
        Check.that("the textarea still holds what was written in it, escaped",
                markup.contains(">a &lt;b&gt;note&lt;/b&gt;</textarea>"));
        Check.that("the select still remembers what was chosen",
                markup.contains("<option value=\"record\" selected>"));
        Check.that("each problem is beside its own field",
                markup.contains("<p class=\"field-problem\" id=\"limit-problem\">must be a whole number</p>"));
        Check.that("a field that failed says so to a screen reader too",
                markup.contains("aria-invalid=\"true\" aria-describedby=\"limit-problem\""));
        Check.that("a field that did not fail says nothing of the sort",
                markup.contains("<div class=\"field\"><label for=\"kind\">"));
    }

    private static void rendering() {
        var blank = form().blank();
        var markup = Forms.html(blank).markup();
        Check.that("a blank form is a form", markup.startsWith("<form method=\"post\" action=\"/symbols\""));
        Check.that("a label points at its input", markup.contains("<label for=\"name\">Symbol</label>"));
        Check.that("a required field says so in the markup as well as in the rules",
                markup.contains("id=\"name\" name=\"name\" required"));
        Check.that("a hint is described rather than only shown",
                markup.contains("aria-describedby=\"name-hint\""));
        Check.that("a checkbox is written with the hidden input that makes an unticked one send something",
                markup.contains("<input type=\"hidden\" name=\"all\" value=\"0\">"
                        + "<input id=\"all\" name=\"all\" type=\"checkbox\" value=\"1\">"));
        Check.that("nothing is invalid before anything has been submitted", !markup.contains("aria-invalid"));

        var patch = Form.at("/symbols/1").method("patch").with(Field.text("name")).blank();
        var overridden = Forms.html(patch).markup();
        Check.that("a form that means PATCH is still submitted as a POST",
                overridden.startsWith("<form method=\"post\""));
        Check.that("and says what it meant", overridden.contains("name=\"_method\" value=\"patch\""));

        var general = Form.at("/x").with(Field.text("a"))
                .check("", "nothing adds up", values -> false)
                .capture(submitted("a", "1"));
        Check.that("problems belonging to the form are announced",
                Forms.html(general).markup().contains("<ul class=\"form-problems\" role=\"alert\">"));
        Check.that("and nothing is announced when there are none",
                !Forms.html(form().blank()).markup().contains("form-problems"));

        var one = Forms.field(form().blank(), "limit").markup();
        Check.that("a field can be drawn on its own, for a layout this package should not invent",
                one.startsWith("<div class=\"field\"><label for=\"limit\">"));

        var hidden = Form.at("/x").with(Field.hidden("token")).showing(Json.Object.of().with("token", "abc"));
        Check.equal("a hidden field is only its input — a label for something nobody sees is noise",
                "<input id=\"token\" name=\"token\" type=\"hidden\" value=\"abc\">",
                Forms.field(hidden, "token").markup());
    }

    /// The other direction of the round trip: a form for something that already
    /// exists.
    private static void editing() {
        var showing = form().showing(Json.Object.of()
                .with("name", "json.Json")
                .with("limit", 20)
                .with("all", true)
                .with("kind", "interface"));
        var markup = Forms.html(showing).markup();
        Check.that("an existing value is in the input", markup.contains("value=\"json.Json\""));
        Check.that("a number is rendered as the text a browser would have sent",
                markup.contains("value=\"20\""));
        Check.that("a true checkbox is ticked", markup.contains("type=\"checkbox\" value=\"1\" checked"));
        Check.that("the right option is selected", markup.contains("<option value=\"interface\" selected>"));
        Check.that("and nothing is wrong with a form nobody has submitted", showing.ok());
    }

    private static void messages() throws IOException {
        var captured = form().capture(submitted("name", "json.Json", "limit", "5"));
        var message = captured.message("symbols.search");
        Check.equal("a submission is a message an update function can act on", "symbols.search", message.type());
        Check.that("carrying the values", message.get("values").text().contains("\"name\":\"json.Json\""));
        Check.that("and whether they can be trusted", message.flag("ok"));

        var rejected = form().capture(submitted("limit", "seven"));
        var failed = rejected.message("symbols.search");
        Check.that("a failed one says so rather than throwing", !failed.flag("ok"));
        Check.that("and carries what was wrong, as data",
                failed.get("problems").text().contains("\"message\":\"must be a whole number\""));

        var request = Request.of("POST", "/symbols",
                Headers.of("Content-Type", "application/x-www-form-urlencoded"),
                Request.body("name=json.Json&limit=3"));
        var body = form().capture(request);
        Check.equal("a form reads a request through the same parameters as everything else in web",
                "json.Json", body.text("name", ""));
        Check.equal("including the numbers in it", Json.of(3), body.values().get("limit"));
    }

    private static void naming() {
        Check.equal("a label is derived from the name when nobody gives one",
                "Due date", Field.text("due_date").label());
        Check.equal("however the name was written", "Due date", Field.text("dueDate").label());
        Check.equal("and a given one is used as it is", "When", Field.text("due_date").label("When").label());
        Check.equal("an id is a name a browser can point a label at",
                "person-name", Field.text("person[name]").id());
    }

    private static void defining() {
        Check.throwing("two fields with one name is a mistake in the form, not in a submission",
                () -> Form.at("/x").with(Field.text("a"), Field.text("a")));
        Check.throwing("so is asking a form about a field it does not have",
                () -> Form.at("/x").with(Field.text("a")).field("b"));
        Check.throwing("and a field with no name at all", () -> Field.text(" "));
    }
}
