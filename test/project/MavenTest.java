package project;

import harness.Check;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MavenTest {
    private static final URI REPOSITORY = URI.create("https://repo.test/");

    private MavenTest() {}

    public static void run() throws Exception {
        directRootWins();
        declarationOrderWins();
        scopesOptionalAndExclusions();
        managementClassifiersAndRelocation();
        unsupportedFeaturesFail();
    }

    private static void directRootWins() throws Exception {
        var poms = new LinkedHashMap<String, String>();
        add(poms, "g:first:1", dependencies(dependency("g", "common", "1", "")));
        add(poms, "g:common:1", "");
        add(poms, "g:common:2", "");
        var resolution = resolve(poms, "g:first:1", "g:common:2");
        Check.equal("a direct root wins over a transitive version",
                List.of("g:first:1", "g:common:2"), coordinates(resolution.runtime()));
        Check.equal("the displaced transitive version is explained",
                "g:common:1 -> g:common:2", resolution.omitted().getFirst().node().coordinate().text()
                        + " -> " + resolution.omitted().getFirst().selected().text());
    }

    private static void declarationOrderWins() throws Exception {
        var poms = new LinkedHashMap<String, String>();
        add(poms, "g:root:1", dependencies(
                dependency("g", "left", "1", "") + dependency("g", "right", "1", "")));
        add(poms, "g:left:1", dependencies(dependency("g", "common", "1", "")));
        add(poms, "g:right:1", dependencies(dependency("g", "common", "2", "")));
        add(poms, "g:common:1", "");
        add(poms, "g:common:2", "");
        var resolution = resolve(poms, "g:root:1");
        Check.that("the first equal-depth declaration wins",
                coordinates(resolution.runtime()).contains("g:common:1")
                        && !coordinates(resolution.runtime()).contains("g:common:2"));
    }

    private static void scopesOptionalAndExclusions() throws Exception {
        var poms = new LinkedHashMap<String, String>();
        add(poms, "g:root:1", dependencies(
                dependency("g", "optional", "1", "<optional>true</optional>")
                + dependency("g", "testing", "1", "<scope>test</scope>")
                + dependency("g", "provided", "1", "<scope>provided</scope>")
                + dependency("g", "left", "1", "<exclusions><exclusion><groupId>g</groupId>"
                        + "<artifactId>leaf</artifactId></exclusion></exclusions>")
                + dependency("g", "right", "1", "")));
        add(poms, "g:optional:1", "");
        add(poms, "g:testing:1", "");
        add(poms, "g:provided:1", "");
        add(poms, "g:left:1", dependencies(dependency("g", "leaf", "1", "")));
        add(poms, "g:right:1", dependencies(dependency("g", "leaf", "1", "")));
        add(poms, "g:leaf:1", "");
        var resolution = resolve(poms, "g:root:1");
        Check.that("optional transitives stay out of both graphs",
                !coordinates(resolution.runtime()).contains("g:optional:1")
                        && !coordinates(resolution.test()).contains("g:optional:1"));
        Check.that("test and provided scopes stay out of runtime",
                !coordinates(resolution.runtime()).contains("g:testing:1")
                        && !coordinates(resolution.runtime()).contains("g:provided:1"));
        Check.that("test and provided scopes enter the test graph",
                coordinates(resolution.test()).containsAll(List.of("g:testing:1", "g:provided:1")));
        var leaf = resolution.runtime().stream().filter(node -> node.coordinate().artifact().equals("leaf")).findFirst().orElseThrow();
        Check.equal("an exclusion applies only to its declaring path",
                List.of("g:root:1", "g:right:1", "g:leaf:1"), leaf.path());
    }

    private static void managementClassifiersAndRelocation() throws Exception {
        var poms = new LinkedHashMap<String, String>();
        add(poms, "g:parent:1", "<packaging>pom</packaging><properties><managed.version>3</managed.version></properties>"
                + "<dependencyManagement>" + dependencies(dependency("g", "from-parent", "${managed.version}", ""))
                + "</dependencyManagement>");
        add(poms, "g:bom:1", "<packaging>pom</packaging><dependencyManagement>"
                + dependencies(dependency("g", "from-bom", "4", "")) + "</dependencyManagement>");
        add(poms, "g:root:1", "<parent><groupId>g</groupId><artifactId>parent</artifactId><version>1</version></parent>"
                + "<dependencyManagement>" + dependencies(dependency("g", "bom", "1",
                        "<type>pom</type><scope>import</scope>")) + "</dependencyManagement>"
                + dependencies(dependency("g", "from-parent", null, "")
                        + dependency("g", "from-bom", null, "")
                        + dependency("g", "fixture", "1", "<type>test-jar</type><scope>test</scope>")
                        + dependency("g", "old", "1", "")));
        add(poms, "g:from-parent:3", "");
        add(poms, "g:from-bom:4", "");
        add(poms, "g:fixture:1", "");
        add(poms, "g:old:1", "<distributionManagement><relocation><groupId>g</groupId>"
                + "<artifactId>new</artifactId><version>2</version></relocation></distributionManagement>");
        add(poms, "g:new:2", "");
        var resolution = resolve(poms, "g:root:1");
        Check.that("parent and imported BOM management supply versions",
                coordinates(resolution.runtime()).containsAll(List.of("g:from-parent:3", "g:from-bom:4")));
        var fixture = resolution.test().stream().filter(node -> node.coordinate().artifact().equals("fixture"))
                .findFirst().orElseThrow();
        Check.equal("test-jar creates classifier identity", "g:fixture:test-jar:tests",
                fixture.coordinate().conflictKey());
        var relocated = resolution.runtime().stream().filter(node -> node.coordinate().artifact().equals("new"))
                .findFirst().orElseThrow();
        Check.equal("relocation selects and records the replacement", "g:old:1 -> g:new:2",
                relocated.relocatedFrom() + " -> " + relocated.coordinate().text());
    }

    private static void unsupportedFeaturesFail() throws Exception {
        var unresolved = Map.of("g:root:1", pom("g", "root", "1",
                dependencies(dependency("g", "broken", "${missing}", ""))));
        Check.that("an unresolved property fails explicitly", failure(unresolved).contains("unresolved Maven property"));
        var unknown = Map.of("g:root:1", pom("g", "root", "1",
                dependencies(dependency("g", "native", "1", "<type>nar</type>"))));
        Check.that("an unknown type fails explicitly", failure(unknown).contains("unsupported Maven artifact type 'nar'"));
    }

    private static Maven.Resolution resolve(Map<String, String> poms, String... roots) throws Exception {
        return new Maven.Resolver(coordinate -> {
            var key = coordinate.group() + ":" + coordinate.artifact() + ":" + coordinate.version();
            var xml = poms.get(key);
            if (xml == null) throw new IOException("missing fixture POM " + coordinate.text());
            return new Maven.PomDocument(xml, REPOSITORY);
        }).resolve(java.util.Arrays.stream(roots).map(Add.Coordinate::parse).toList());
    }

    private static String failure(Map<String, String> poms) throws Exception {
        try {
            resolve(poms, "g:root:1");
            return "";
        } catch (IOException expected) {
            return expected.getMessage();
        }
    }

    private static List<String> coordinates(List<Maven.Node> nodes) {
        return nodes.stream().map(node -> node.coordinate().text()).toList();
    }

    private static void add(Map<String, String> poms, String coordinate, String body) {
        var parsed = Add.Coordinate.parse(coordinate);
        poms.put(coordinate, pom(parsed.group(), parsed.artifact(), parsed.version(), body));
    }

    private static String pom(String group, String artifact, String version, String body) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>"
                + artifact + "</artifactId><version>" + version + "</version>" + body + "</project>";
    }

    private static String dependencies(String body) {
        return "<dependencies>" + body + "</dependencies>";
    }

    private static String dependency(String group, String artifact, String version, String body) {
        return "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId>"
                + (version == null ? "" : "<version>" + version + "</version>") + body + "</dependency>";
    }
}
