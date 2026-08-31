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
        initializesAndValidates();
        decodesEnvironmentValues();
        protectsSecretReferences();
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
                    ((Json.Num) ((Json.Object) ((Json.Object) ((Json.Object) system.inspect(Settings.address()))
                            .get("values")).get("numbers")).get("number")).value());
            var rejected = system.ask(Settings.address(), settings.set("/numbers/number", Json.of(-1)),
                    Duration.ofSeconds(1)).join();
            Check.equal("invalid settings are rejected", "settings.rejected", rejected.type());
            Thread.sleep(50);
            var inspected = (Json.Object) system.inspect(Settings.address());
            Check.equal("invalid settings do not change revision", 1.0,
                    ((Json.Num) inspected.get("revision")).value());
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
            var values = (Json.Object) ((Json.Object) system.inspect(Settings.address())).get("values");
            Check.equal("environment initialization is decoded", "tuul",
                    ((Json.Str) ((Json.Object) values.get("environment")).get("name")).value());
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
