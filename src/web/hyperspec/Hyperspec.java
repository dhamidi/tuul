package web.hyperspec;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import web.hyperspec.Script.Command;
import web.hyperspec.Script.Part;
import web.hyperspec.Script.Word;

/// Runs a spec against a live service.
///
/// A hyperspec describes a journey — arrive somewhere, see what is offered,
/// use it, arrive somewhere else — and asserts on what the application offers
/// rather than on how it is written. There is no command here for looking at
/// the text of a page, and that is the design: a test that asserts on wording
/// fails when the wording changes, which teaches everybody to stop writing
/// tests.
///
/// The service is always a running one. `$service` is injected by the runner
/// and is an ordinary variable, so a spec never names a port and can be pointed
/// at anything that speaks the application's protocol.
///
/// ```
/// visit /
/// expect link "Sign in"
/// follow "Sign in"
/// fill name alice
/// submit
/// expect status 303
/// follow redirect
/// expect item note
/// set id [attribute note id]
/// ```
///
/// One failure stops the client it happened to. A journey is a sequence, and
/// everything after a wrong turn is a report about a page nobody meant to be
/// on. Other clients carry on, which is what makes the concurrent case useful.
public final class Hyperspec {

    /// The variable the service's address arrives in.
    public static final String SERVICE = "service";

    private static final List<String> COMMANDS = List.of(
            "set", "visit", "follow", "fill", "submit", "expect",
            "status", "at", "location", "attribute", "link", "count", "requests",
            "client", "concurrently");

    private final URI service;
    private final Map<String, String> globals = new ConcurrentHashMap<>();
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final Queue<Outcome.Check> checks = new ConcurrentLinkedQueue<>();
    private final Queue<Outcome.Failure> failures = new ConcurrentLinkedQueue<>();

    private Hyperspec(URI service) {
        this.service = service;
        globals.put(SERVICE, service.toString());
    }

    public static Outcome run(String spec, URI service) {
        return new Hyperspec(service).run(Syntax.parse(spec));
    }

    public static Outcome run(Path spec, URI service) throws IOException {
        return run(Files.readString(spec, StandardCharsets.UTF_8), service);
    }

    private Outcome run(Script script) {
        var client = client("default");
        client.attempt(() -> client.run(script));
        return new Outcome(new ArrayList<>(checks), new ArrayList<>(failures));
    }

    private Client client(String name) {
        return clients.computeIfAbsent(name, Client::new);
    }

    /// A failure, thrown so that the rest of a journey does not run. It is not
    /// a [SpecException] because it is not a mistake in the spec: the spec was
    /// right and the application was not.
    private static final class Failed extends RuntimeException {

        private final String client;
        private final int line;
        private final String what;
        private final String found;

        private Failed(String client, int line, String what, String found) {
            super(what);
            this.client = client;
            this.line = line;
            this.what = what;
            this.found = found;
        }

        private Outcome.Failure asFailure() {
            return new Outcome.Failure(client, line, what, found);
        }
    }

    /// One client, running its own script against its own session.
    private final class Client {

        private final String name;
        private final Agent agent;
        private final Map<String, String> variables;
        private final Map<String, String> filled = new LinkedHashMap<>();

        private Client(String name) {
            this.name = name;
            this.agent = new Agent(name, service);
            // The spec's own scope is the shared one: a value captured before
            // anybody signs in belongs to the spec, not to a client.
            this.variables = name.equals("default") ? globals : new LinkedHashMap<>();
        }

        private String run(Script script) {
            var result = "";
            for (var command : script.commands()) result = command(command);
            return result;
        }

        private String command(Command command) {
            if (command.isEmpty()) return "";
            var line = command.line();
            var name = word(command.words().getFirst());
            try {
                return switch (name) {
                    case "client" -> client(command, line);
                    case "concurrently" -> concurrently(command, line);
                    default -> apply(name, arguments(command), line);
                };
            } catch (SpecException e) {
                // Something that failed below the spec — a connection refused,
                // a redirect that is not there — knows what went wrong and not
                // where it was asked for. This is where that is known.
                throw e.line() == 0 ? new SpecException(line, e.getMessage()) : e;
            }
        }

        /// Runs something, recording a failure against *this* client rather
        /// than letting it escape to whoever called. A client's journey ends at
        /// its first failure; the file it is written in carries on.
        private void attempt(Runnable body) {
            try {
                body.run();
            } catch (Failed failed) {
                failures.add(failed.asFailure());
            } catch (SpecException e) {
                failures.add(new Outcome.Failure(name, e.line(), e.getMessage(), ""));
            }
        }

        /// `client` and `concurrently` take a body rather than a value, so
        /// their words are not all evaluated: a braced word is source, and
        /// evaluating it would turn a script into a string.
        private String client(Command command, int line) {
            var words = command.words();
            if (words.size() != 3 || !words.get(2).braced()) {
                throw new SpecException(line, "client takes a name and a body: client alice { ... }");
            }
            var body = words.get(2);
            var client = Hyperspec.this.client(word(words.get(1)));
            var script = Syntax.parse(body.body(), body.line());
            client.attempt(() -> client.run(script));
            return "";
        }

        /// Every client in the body at once, each in its own virtual thread and
        /// its own session. The block does not return until all of them have
        /// finished, which is what makes a spec deterministic even though what
        /// is inside it is not.
        private String concurrently(Command command, int line) {
            var words = command.words();
            if (words.size() != 2 || !words.get(1).braced()) {
                throw new SpecException(line, "concurrently takes a body: concurrently { client a { ... } }");
            }
            var body = words.get(1);
            var script = Syntax.parse(body.body(), body.line());
            for (var each : script.commands()) {
                if (!each.name().equals("client")) {
                    throw new SpecException(each.line(),
                            "concurrently holds client blocks and nothing else, and this is " + describe(each));
                }
            }
            try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
                for (var each : script.commands()) threads.submit(() -> separately(each));
            }
            return "";
        }

        /// One client of a `concurrently` block. Its failure is its own: the
        /// point of running two people at once is to see what each of them
        /// gets, and stopping the other would throw that away.
        private void separately(Command command) {
            var words = command.words();
            var name = words.size() > 1 ? word(words.get(1)) : "";
            Hyperspec.this.client(name).attempt(() -> command(command));
        }

        private List<String> arguments(Command command) {
            return command.words().stream().skip(1).map(this::word).toList();
        }

        private String apply(String name, List<String> arguments, int line) {
            return switch (name) {
                case "set" -> set(arguments, line);
                case "visit" -> visit(arguments, line);
                case "follow" -> follow(arguments, line);
                case "fill" -> fill(arguments, line);
                case "submit" -> submit(arguments, line);
                case "expect" -> Expect.of(this).run(arguments, line);
                case "status" -> String.valueOf(page(line).status());
                case "at" -> agent.at();
                case "location" -> page(line).location().orElse("");
                case "requests" -> String.valueOf(agent.history().size());
                case "attribute" -> attribute(arguments, line);
                case "link" -> link(arguments, line).href();
                case "count" -> count(arguments, line);
                default -> throw new SpecException(line,
                        "unknown command \"" + name + "\" — this spec knows " + String.join(", ", COMMANDS));
            };
        }

        private String set(List<String> arguments, int line) {
            if (arguments.size() != 2) throw new SpecException(line, "set takes a name and a value");
            variables.put(arguments.get(0), arguments.get(1));
            return arguments.get(1);
        }

        private String visit(List<String> arguments, int line) {
            if (arguments.size() != 1) throw new SpecException(line, "visit takes a path");
            filled.clear();
            agent.visit(arguments.getFirst());
            return "";
        }

        private String follow(List<String> arguments, int line) {
            if (arguments.size() != 1) throw new SpecException(line, "follow takes a link, or the word redirect");
            filled.clear();
            if (arguments.getFirst().equals("redirect")) {
                if (!page(line).redirect()) {
                    unmet(line, "expected a redirect to follow", "the last response was " + page(line).status());
                }
                agent.followRedirect();
                return "";
            }
            agent.follow(link(arguments, line));
            return "";
        }

        private String fill(List<String> arguments, int line) {
            if (arguments.size() != 2) throw new SpecException(line, "fill takes a field and a value");
            var field = arguments.get(0);
            var offered = page(line).forms().stream().anyMatch(form -> form.field(field).isPresent());
            if (!offered) unmet(line, "no field \"" + field + "\" to fill in", "this page offers " + fields());
            filled.put(field, arguments.get(1));
            return "";
        }

        private String submit(List<String> arguments, int line) {
            if (arguments.size() > 1) throw new SpecException(line, "submit takes a form, or nothing");
            var wanted = arguments.isEmpty() ? "" : arguments.getFirst();
            var form = page(line).form(wanted).orElseThrow(() -> failure(line,
                    "no form " + (wanted.isEmpty() ? "to submit" : "\"" + wanted + "\""),
                    "this page offers " + forms()));
            agent.submit(form, filled);
            filled.clear();
            return "";
        }

        private String attribute(List<String> arguments, int line) {
            if (arguments.size() != 2) throw new SpecException(line, "attribute takes a resource and a name");
            var item = page(line).item(arguments.get(0)).orElseThrow(() -> failure(line,
                    "no " + arguments.get(0) + " on this page", "it describes " + resources()));
            return item.attribute(arguments.get(1)).orElseThrow(() -> failure(line,
                    arguments.get(0) + " has no " + arguments.get(1),
                    "it has " + item.attributes().keySet()));
        }

        private Page.Link link(List<String> arguments, int line) {
            return page(line).link(arguments.getFirst()).orElseThrow(() -> failure(line,
                    "no link \"" + arguments.getFirst() + "\"", "this page offers " + labels()));
        }

        private String count(List<String> arguments, int line) {
            if (arguments.isEmpty()) throw new SpecException(line, "count takes link, form or item");
            var page = page(line);
            return switch (arguments.getFirst()) {
                case "link" -> String.valueOf(page.links().size());
                case "form" -> String.valueOf(page.forms().size());
                case "item" -> String.valueOf(arguments.size() > 1
                        ? page.items(arguments.get(1)).size()
                        : page.items().size());
                default -> throw new SpecException(line, "count takes link, form or item");
            };
        }

        private Page page(int line) {
            var page = agent.page();
            if (page == null) throw new SpecException(line, "this client has not visited anything yet");
            return page;
        }

        /// A word, evaluated: text as written, a variable looked up, a
        /// `[script]` run and its result standing where it was written.
        private String word(Word word) {
            if (word.braced()) return word.body();
            var text = new StringBuilder();
            for (var part : word.parts()) {
                switch (part) {
                    case Part.Text(var value) -> text.append(value);
                    case Part.Variable(var name) -> text.append(variable(name, word.line()));
                    case Part.Substitution(var script) -> text.append(run(script));
                }
            }
            return text.toString();
        }

        private String variable(String name, int line) {
            var value = variables.get(name);
            if (value != null) return value;
            var global = globals.get(name);
            if (global != null) return global;
            throw new SpecException(line, "no variable named $" + name);
        }

        private List<String> labels() {
            return page(0).links().stream().map(Page.Link::label).toList();
        }

        private List<String> forms() {
            return page(0).forms().stream().map(form -> form.name().isEmpty() ? "(unnamed)" : form.name()).toList();
        }

        private List<String> fields() {
            return page(0).forms().stream()
                    .flatMap(form -> form.fields().stream())
                    .map(Page.Field::name)
                    .filter(name -> !name.isEmpty())
                    .toList();
        }

        private List<String> resources() {
            return page(0).items().stream().map(Page.Item::type).toList();
        }

        private void met(int line, String what) {
            checks.add(new Outcome.Check(name, line, what));
        }

        private void unmet(int line, String what, String found) {
            throw failure(line, what, found);
        }

        private Failed failure(int line, String what, String found) {
            return new Failed(name, line, what, found);
        }
    }

    private static String describe(Command command) {
        return command.name().isEmpty() ? "something else" : "\"" + command.name() + "\"";
    }

    /// The assertions. Split out because there are more of them than of
    /// anything else, and because every one of them is the same shape: ask the
    /// page what it offers, and say what it offered when the answer is no.
    private record Expect(Client client) {

        private static Expect of(Client client) {
            return new Expect(client);
        }

        private String run(List<String> arguments, int line) {
            if (arguments.isEmpty()) throw new SpecException(line, "expect takes something to expect");
            var negated = arguments.getFirst().equals("no");
            var rest = negated ? arguments.subList(1, arguments.size()) : arguments;
            if (rest.isEmpty()) throw new SpecException(line, "expect no takes something to not expect");
            var page = client.page(line);
            switch (rest.getFirst()) {
                case "status" -> status(rest, line);
                case "at" -> at(rest, line);
                case "requests" -> requests(rest, line);
                case "redirect" -> redirect(rest, line, page);
                case "link" -> offered(line, negated, "link", rest, page.link(argument(rest, line)).isPresent(),
                        client.labels());
                case "form" -> offered(line, negated, "form", rest, page.form(argument(rest, line)).isPresent(),
                        client.forms());
                case "item" -> offered(line, negated, "item", rest, !page.items(argument(rest, line)).isEmpty(),
                        client.resources());
                case "field" -> field(rest, line, page, negated);
                case "attribute" -> attribute(rest, line, negated);
                default -> throw new SpecException(line, "expect does not know \"" + rest.getFirst()
                        + "\" — it knows status, at, requests, redirect, link, form, field, item and attribute");
            }
            return "";
        }

        private static String argument(List<String> rest, int line) {
            if (rest.size() < 2) throw new SpecException(line, "expect " + rest.getFirst() + " takes a name");
            return rest.get(1);
        }

        private void status(List<String> rest, int line) {
            var wanted = argument(rest, line);
            var actual = String.valueOf(client.page(line).status());
            if (!wanted.equals(actual)) client.unmet(line, "expected status " + wanted, "it answered " + actual);
            client.met(line, "status " + wanted);
        }

        private void at(List<String> rest, int line) {
            var wanted = argument(rest, line);
            if (!client.agent.at().equals(wanted)) {
                client.unmet(line, "expected to be at " + wanted, "this client is at " + client.agent.at());
            }
            client.met(line, "at " + wanted);
        }

        private void requests(List<String> rest, int line) {
            var wanted = argument(rest, line);
            var made = client.agent.history().size();
            if (!wanted.equals(String.valueOf(made))) {
                client.unmet(line, "expected " + wanted + " requests", "this client made " + made + ": "
                        + client.agent.history().stream()
                                .map(exchange -> exchange.method() + " " + exchange.path() + " " + exchange.status())
                                .toList());
            }
            client.met(line, wanted + " requests");
        }

        private void redirect(List<String> rest, int line, Page page) {
            var wanted = argument(rest, line);
            var where = page.location().orElse("");
            if (!page.redirect() || !where.equals(wanted)) {
                client.unmet(line, "expected a redirect to " + wanted,
                        page.redirect() ? "it redirects to " + where : "it answered " + page.status());
            }
            client.met(line, "redirect to " + wanted);
        }

        private void field(List<String> rest, int line, Page page, boolean negated) {
            if (rest.size() < 3) throw new SpecException(line, "expect field takes a form and a field");
            var form = page.form(rest.get(1));
            var has = form.isPresent() && form.get().field(rest.get(2)).isPresent();
            if (has == negated) {
                client.unmet(line, (negated ? "expected no field " : "expected a field ")
                        + rest.get(2) + " in " + rest.get(1), "this page offers " + client.fields());
            }
            client.met(line, (negated ? "no field " : "field ") + rest.get(2) + " in " + rest.get(1));
        }

        private void attribute(List<String> rest, int line, boolean negated) {
            if (rest.size() < 4) {
                throw new SpecException(line, "expect attribute takes a resource, a name and a value");
            }
            var item = client.page(line).item(rest.get(1));
            var actual = item.flatMap(found -> found.attribute(rest.get(2)));
            var matches = actual.filter(value -> value.equals(rest.get(3))).isPresent();
            if (matches == negated) {
                client.unmet(line,
                        (negated ? "expected " : "expected ") + rest.get(1) + " " + rest.get(2)
                                + (negated ? " not to be " : " to be ") + rest.get(3),
                        item.isEmpty()
                                ? "this page describes " + client.resources()
                                : "it is " + actual.map(value -> "\"" + value + "\"").orElse("absent"));
            }
            client.met(line, rest.get(1) + " " + rest.get(2) + " is " + rest.get(3));
        }

        /// The four assertions that are all the same question: does the page
        /// offer this, and was that what the spec wanted?
        private void offered(int line, boolean negated, String kind, List<String> rest,
                boolean present, List<String> available) {
            var name = argument(rest, line);
            if (present == negated) {
                client.unmet(line, negated ? "expected no " + kind + " \"" + name + "\"" : "no " + kind + " \"" + name + "\"",
                        "this page offers " + available);
            }
            client.met(line, (negated ? "no " : "") + kind + " \"" + name + "\"");
        }
    }
}
