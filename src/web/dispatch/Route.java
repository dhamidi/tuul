package web.dispatch;

import java.util.List;
import java.util.Locale;
import uritemplates.Part;
import uritemplates.Template;
import uritemplates.TemplateException;

/// A name, a method and a URI template — the whole of what a route is.
///
/// The name is what an application refers to, so that nothing writes a URL by
/// hand: renaming a route then breaks the place that builds the URL, which a
/// compiler or a test will find, rather than producing a 404 at run time, which
/// only a user will.
///
/// The template must be one that can be read backwards, and that is checked
/// here rather than when a request arrives. A template a router cannot
/// recognise is a mistake in the route table, and the difference between
/// finding it in a test and finding it in production is only where it is
/// refused.
public record Route(String name, String method, Template template) {

    public Route {
        if (name.isBlank()) throw new DispatchException("a route needs a name");
        if (method.isBlank()) throw new DispatchException("route " + name + " needs a method");
        method = method.toUpperCase(Locale.ROOT);
        if (!template.matchable()) {
            throw new DispatchException("route " + name + " cannot be recognised: " + template.text()
                    + " — only {var} and {/var}, without modifiers, can be read backwards");
        }
    }

    public static Route of(String name, String method, String template) {
        return new Route(name, method, read(name, template));
    }

    /// Whether this route answers a request made with this method. A GET route
    /// answers HEAD as well, because the two differ in what is sent back rather
    /// than in what is being asked for, and saying so twice in a route table is
    /// how the two drift apart.
    public boolean accepts(String method) {
        return this.method.equals(method) || (this.method.equals("GET") && method.equals("HEAD"));
    }

    /// The methods this route answers, which is what a 405 has to report.
    public List<String> answers() {
        return method.equals("GET") ? List.of("GET", "HEAD") : List.of(method);
    }

    /// How many characters of the template are fixed text. More of them means a
    /// more specific route — see [Router#recognise].
    public int literals() {
        var length = 0;
        for (var part : template.parts()) {
            if (part instanceof Part.Literal(var text)) length += text.length();
        }
        return length;
    }

    /// How many variables the template has, counting a variable named twice
    /// twice, since it is written twice.
    public int variables() {
        var count = 0;
        for (var part : template.parts()) {
            if (part instanceof Part.Expression expression) count += expression.variables().size();
        }
        return count;
    }

    private static Template read(String name, String text) {
        try {
            return Template.of(text);
        } catch (TemplateException e) {
            throw new DispatchException("route " + name + " has no usable template: " + e.getMessage());
        }
    }

    @Override
    public String toString() {
        return method + " " + template.text() + " (" + name + ")";
    }
}
