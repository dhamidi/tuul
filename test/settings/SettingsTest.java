package settings;

import actors.ActorSystem;
import application.Message;
import harness.Check;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import json.Json;

public final class SettingsTest {

    private SettingsTest() {}

    public static void run() throws Exception {
        validatesRuntimeValues();
        initializesAndValidates();
        updatesArrays();
        decodesEnvironmentValues();
        protectsSecretReferences();
    }

    private static void validatesRuntimeValues() {
        var schema = Json.parse("""
                {"type":"object","properties":{"name":{"type":"string"}},
                 "required":["name"],"additionalProperties":false}
                """);
        var configuration = Configuration.of(Contribution.named("application", schema));
        var values = configuration.values(Json.Object.of()
                .with("application", Json.Object.of("name", "tuul")));
        Check.equal("runtime values keep the validated document", "tuul",
                ((Json.Str) values.find("/application/name").orElseThrow()).value());
        var settings = Settings.of(configuration);
        Check.equal("the actor uses the same configuration", "tuul",
                ((Json.Str) settings.values(values.document()).find("/application/name").orElseThrow()).value());
        Check.that("runtime values find an absent pointer", values.find("/application/missing").isEmpty());
        Check.throwing("runtime values reject an unknown namespace", () -> configuration.values(
                Json.Object.of("unknown", Json.Object.of())));
        Check.throwing("runtime values reject an invalid namespace", () -> configuration.values(
                Json.Object.of("application", Json.Object.of("name", 1))));
    }

    private static void initializesAndValidates() throws Exception {
        var schema = Json.parse("""
                {"type":"object","properties":{"number":{"type":"integer","minimum":1}},
                 "required":["number"],"additionalProperties":false}
                """);
        var settings = Settings.of(Contribution.named("numbers", schema)
                .initially("/number", Initial.value(Json.of(2))));
        try (var system = ActorSystem.named("settings-test").define(settings)) {
            system.summon(Settings.address());
            Thread.sleep(50);
            Check.equal("constant initialization is durable", 2.0,
                    system.inspect(Settings.address()).at("/values/numbers/number").number(0));
            var rejected = system.ask(Settings.address(), settings.set("/numbers/number", Json.of(-1)),
                    Duration.ofSeconds(1)).join();
            Check.equal("invalid settings are rejected", "settings.rejected", rejected.type());
            Thread.sleep(50);
            var inspected = (Json.Object) system.inspect(Settings.address());
            Check.equal("invalid settings do not change revision", 1.0,
                    inspected.at("/revision").number(0));
        }
    }

    private static void decodesEnvironmentValues() throws Exception {
        var schema = Json.parse("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}");
        var settings = Settings.of(Contribution.named("environment", schema)
                .initially("/name", Initial.environment("NAME", Initial.string())));
        try (var system = ActorSystem.named("settings-environment").define(settings)
                .effect(Settings.RESOLVE, Initializers.environment(name -> name.equals("NAME") ? "tuul" : null))) {
            system.summon(Settings.address());
            Thread.sleep(50);
            Check.equal("environment initialization is decoded", "tuul",
                    system.inspect(Settings.address()).at("/values/environment/name").string(""));
        }
    }

    private static void updatesArrays() throws Exception {
        var schema = Json.parse("""
                {"type":"object","properties":{"items":{"type":"array","items":{"type":"string"}}},
                 "required":["items"]}
                """);
        var settings = Settings.of(Contribution.named("lists", schema)
                .initially("/items", Initial.value(Json.parse("[\"a\",\"b\"]"))));
        try (var system = ActorSystem.named("settings-arrays").define(settings)) {
            system.summon(Settings.address());
            Thread.sleep(50);
            var replaced = system.ask(Settings.address(), settings.set("/lists/items/1", Json.of("B")),
                    Duration.ofSeconds(1)).join();
            Check.equal("a settings pointer replaces an array item", "settings.changed", replaced.type());
            var appended = system.ask(Settings.address(), settings.set("/lists/items/-", Json.of("c")),
                    Duration.ofSeconds(1)).join();
            Check.equal("a settings pointer appends an array item", "settings.changed", appended.type());
            var removed = system.ask(Settings.address(), settings.unset("/lists/items/0"),
                    Duration.ofSeconds(1)).join();
            Check.equal("a settings pointer removes an array item", "settings.changed", removed.type());
            Check.equal("settings array edits preserve item order", Json.parse("[\"B\",\"c\"]"),
                    system.inspect(Settings.address()).at("/values/lists/items"));
        }
    }

    private static void protectsSecretReferences() {
        var schema = Json.parse("{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"object\"}}}");
        var settings = Settings.of(Contribution.named("credentials", schema).secret("/key"));
        Check.throwing("a secret path refuses ordinary values", () ->
                settings.set("/credentials/key", Json.Object.of().with("value", "secret")));
        Check.that("a secret reference has the exact shape",
                Secret.isReference(Secret.reference("environment:KEY")));
    }
}
