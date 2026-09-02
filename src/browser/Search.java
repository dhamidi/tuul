package browser;

import json.Json;
import web.Router;
import web.forms.Field;
import web.forms.Form;
import web.forms.Submission;

/// The search form, defined once.
///
/// It is a form rather than a hand-written input because that is what the rest
/// of the framework expects of a question a person answers: what was typed
/// comes back when a page is re-rendered, and the field's name is said in one
/// place instead of in the markup, the handler and the test separately.
public final class Search {

    /// The parameter a search arrives in, named here so the form, the update
    /// and the spec all mean the same field.
    public static final String QUERY = "q";

    private Search() {}

    public static Form form(Router routes) {
        return Form.named("search", routes.path(Routes.SEARCH))
                .get()
                .with(Field.search(QUERY).label("Search")
                        .placeholder("Type a symbol or search docs")
                        .hint("A type name, or words from its documentation"));
    }

    public static Submission blank(Router routes) {
        return form(routes).blank();
    }

    /// The form as it should look having been asked something — with the
    /// question still in it, which is the point of asking `web.forms` rather
    /// than writing an input.
    public static Submission asking(Router routes, String query) {
        return form(routes).showing(Json.Object.of().with(QUERY, query));
    }
}
