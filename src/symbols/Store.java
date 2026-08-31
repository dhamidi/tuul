package symbols;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sqlite3.Database;
import sqlite3.Statement;

/// What tuul has already worked out about a symbol, kept between runs.
///
/// The expensive part of answering a question about a type is not answering it
/// — it is compiling a source tree to find out, or parsing four thousand lines
/// of `String.java` to read one doc comment. None of that changes between two
/// runs where nothing on disk changed, so none of it is done twice.
///
/// Every fact is filed under the [Origin] it came from. An origin carries the
/// fingerprint that produced its last complete rows. A freshness check does
/// not change those rows. Publication replaces the rows and fingerprint in one
/// transaction.
final class Store implements IndexStore {

    /// Where a body of facts came from, and whether what we have of it is still
    /// good. `complete` says the origin was indexed all at once, so a name that
    /// is missing from it is a name that does not exist — which is what lets a
    /// lookup for something unknown avoid running javac.
    private final Database database;

    private Store(Database database) {
        this.database = database;
    }

    /// Opens the index, building it if there is nothing usable there.
    ///
    /// Somewhere with no room for it, or no permission to write it, is not an
    /// error worth stopping for — tuul answers the question the slow way
    /// instead. A database that will not take the schema is a different matter
    /// and is allowed to fail loudly, because that is a bug rather than a
    /// circumstance.
    static Optional<Store> open(Path file) {
        try {
            return Optional.of(new Store(Schema.open(file)));
        } catch (IOException nowhereToPutIt) {
            return Optional.empty();
        }
    }

    /// Opens an existing index for catalog reads only.
    static Optional<Store> read(Path file) {
        try {
            return Optional.of(new Store(Schema.read(file)));
        } catch (IOException | sqlite3.SqliteException unavailable) {
            return Optional.empty();
        }
    }

    @Override
    public synchronized Optional<Snapshot> inspect(String kind, String location, String stamp) {
        try (var rows = database.query(
                "select id, stamp, complete from origin where kind = ? and location = ?", kind, location)) {
            if (!rows.next()) return Optional.empty();
            return Optional.of(new Snapshot(rows.integer(0), rows.text(1).equals(stamp), rows.integer(2) != 0));
        }
    }

    /// The last complete generation, regardless of whether the source tree has
    /// moved on since it was published.
    synchronized Optional<Long> complete(String kind, String location) {
        try (var rows = database.query(
                "select id from origin where kind = ? and location = ? and complete = 1", kind, location)) {
            return rows.next() ? Optional.of(rows.integer(0)) : Optional.empty();
        }
    }

    synchronized Optional<Long> stored(String kind, String location) {
        try (var rows = database.query(
                "select id from origin where kind = ? and location = ?", kind, location)) {
            return rows.next() ? Optional.of(rows.integer(0)) : Optional.empty();
        }
    }

    /// Everything known about one type, or nothing. The name it answers with is
    /// the one a reader would write, which is not always the one it is filed
    /// under.
    @Override
    public synchronized Optional<TypeInfo> type(long origin, String name) {
        try (var rows = database.query(
                "select id, kind, modifiers, superclass, doc, source, line from type where origin = ? and name = ?",
                origin, name)) {
            if (!rows.next()) return Optional.empty();
            var id = rows.integer(0);
            return Optional.of(new TypeInfo(
                    name.replace('$', '.'),
                    TypeInfo.Kind.valueOf(rows.text(1)),
                    words(rows.text(2)),
                    strings("select name from type_parameter where type = ? order by position", id),
                    rows.text(3),
                    related(id, "implements"),
                    related(id, "permits"),
                    related(id, "nested"),
                    methods(id),
                    fields(id),
                    rows.text(4),
                    tags("select position, tag, name, text from tag where type = ? order by position", id),
                    rows.text(5),
                    (int) rows.integer(6)));
        }
    }

    @Override
    public synchronized Optional<Document> document(long origin, String packageName, String kind, String slug) {
        try (var rows = database.query("""
                select title, body, source from document
                where origin = ? and package = ? and kind = ? and slug = ?
                """, origin, packageName, kind, slug)) {
            if (!rows.next()) return Optional.empty();
            return Optional.of(new Document(packageName, kind, slug, rows.text(0), rows.text(1), rows.text(2)));
        }
    }

    @Override
    public synchronized List<Document> documents(long origin, String packageName, String kind) {
        var found = new ArrayList<Document>();
        var sql = "select kind, slug, title, body, source from document where origin = ? and package = ?"
                + (kind.isEmpty() ? "" : " and kind = ?") + " order by source";
        var parameters = kind.isEmpty()
                ? new Object[] {origin, packageName}
                : new Object[] {origin, packageName, kind};
        try (var rows = database.query(sql, parameters)) {
            while (rows.next()) {
                found.add(new Document(
                        packageName, rows.text(0), rows.text(1), rows.text(2), rows.text(3), rows.text(4)));
            }
        }
        return List.copyOf(found);
    }

    private List<String> related(long type, String relation) {
        var values = new ArrayList<String>();
        try (var rows = database.query(
                "select name from related where type = ? and relation = ? order by position", type, relation)) {
            while (rows.next()) values.add(rows.text(0));
        }
        return List.copyOf(values);
    }

    /// Every type name an origin holds, which is only meaningful for one that
    /// was indexed all at once.
    @Override
    public synchronized List<String> names(long origin) {
        return strings("select name from type where origin = ? order by id", origin);
    }

    /// The names an origin holds of one kind, in alphabetical order — which is
    /// the order a listing is read in, where `names` answers in the order
    /// things were indexed.
    @Override
    public synchronized List<String> names(long origin, TypeInfo.Kind kind) {
        return strings("select name from type where origin = ? and kind = ? order by name", origin, kind.name());
    }

    /// The symbols whose name or documentation match. Ranked by bm25, so the
    /// answer to a two-word question is the symbol that is about both words.
    ///
    /// Names come back the way a person would write them, as they do everywhere
    /// else: a nested type is filed under `Outer$Inner` and read out as
    /// `Outer.Inner`.
    /// FTS5's match syntax is a language of its own, where a dot, a hyphen or a
    /// colon is punctuation with meaning — so `json.Json`, which is exactly what
    /// somebody searching for a symbol types, is a syntax error rather than a
    /// query. Every run of word characters becomes a quoted token and the
    /// tokens are ANDed, which is what the typist meant and cannot fail
    /// whatever they type.
    ///
    /// A query with no word characters in it at all — `...`, `-`, `()` — asks
    /// for nothing, and is answered with nothing. It is not an error: search
    /// runs as somebody types, so the first keystroke of `.Json` would
    /// otherwise show them a database complaining.
    static String query(String typed) {
        var tokens = new ArrayList<String>();
        for (var token : typed.split("[^\\p{IsAlphabetic}\\p{IsDigit}_]+")) {
            if (!token.isBlank()) tokens.add('"' + token + '"');
        }
        return String.join(" ", tokens);
    }

    /// Ranked so that the thing somebody named comes first.
    ///
    /// bm25 alone answers `sqlite3.Database` with nineteen of its own members
    /// before the type itself, because each member's row mentions the name too
    /// and is shorter. So the ordering is stated rather than inherited: what
    /// was typed exactly, then a type called that whatever its package, then
    /// the other types, then members, and bm25 within each.
    ///
    /// The second of those is why `json` finds `json.Json` rather than five of
    /// its nested types: somebody typing a bare name means the thing with that
    /// name, and a package is not something they should have to remember.
    @Override
    public synchronized List<Catalog.Match> search(String text, int limit) {
        var match = query(text);
        if (match.isBlank()) return List.of();

        var found = new ArrayList<Catalog.Match>();
        try (var rows = database.query("""
                select search.symbol, search.kind, search.modifiers,
                    case when search.document is null then search.doc else coalesce((
                        select case when instr(body, char(10)) = 0 then ''
                            else substr(body, instr(body, char(10)) + 1) end
                        from document where document.id = search.document
                    ), '') end,
                    coalesce(origin.kind, ''), coalesce(type.source, document.source, '')
                from search
                left join type on type.id = search.owner
                left join document on document.id = search.document
                left join origin on origin.id = coalesce(type.origin, document.origin)
                where search match ?
                  and coalesce(origin.location, '') <> ?
                order by case
                    when lower(replace(search.symbol, '$', '.')) = lower(?) then 0
                    when search.member is null and lower(replace(search.symbol, '$', '.')) like '%.' || lower(?) then 1
                    when search.member is null then 2
                    else 3
                end, rank
                limit ?
                """, match, Index.platformNavigationLocation(), text.strip(), text.strip(), limit)) {
            while (rows.next()) {
                found.add(new Catalog.Match(
                        rows.text(0).replace('$', '.'), rows.text(1), rows.text(2), rows.text(3),
                        rows.text(4), rows.text(5)));
            }
        }
        return List.copyOf(found);
    }

    @Override
    public synchronized List<Catalog.Root> roots() {
        var found = new ArrayList<Catalog.Root>();
        try (var rows = database.query("select position, name, label from root order by position")) {
            while (rows.next()) {
                found.add(new Catalog.Root(rows.text(1), rows.text(2),
                        strings("select name from root_item where root = ? order by position", rows.integer(0))));
            }
        }
        return List.copyOf(found);
    }

    @Override
    public synchronized void publishRoots(List<Catalog.Root> roots) {
        database.transaction(() -> {
            database.execute("delete from root");
            try (var root = Statement.of(database, "insert into root (position, name, label) values (?, ?, ?)");
                    var item = Statement.of(database,
                            "insert into root_item (root, position, name) values (?, ?, ?)")) {
                for (var at = 0; at < roots.size(); at++) {
                    var written = roots.get(at);
                    root.run(at, written.name(), written.label());
                    for (var in = 0; in < written.contents().size(); in++) {
                        item.run(at, in, written.contents().get(in));
                    }
                }
            }
        });
    }

    /// Builds happen before this method. The transaction begins only when every
    /// replacement row is ready, so readers keep the previous complete origin
    /// until the commit makes this one visible.
    @Override
    public synchronized void publish(String kind, String location, String stamp,
            Map<String, TypeInfo> types, List<Document> documents) {
        database.transaction(() -> {
            var origin = id(kind, location).orElseGet(() -> {
                database.execute("insert into origin (kind, location, stamp) values (?, ?, ?)", kind, location, stamp);
                return database.lastId();
            });
            database.execute("delete from type where origin = ?", origin);
            database.execute("delete from document where origin = ?", origin);
            database.execute("update origin set stamp = ?, complete = 0 where id = ?", stamp, origin);
            try (var sink = new Sink()) {
                types.forEach((name, type) -> sink.put(origin, name, type));
            }
            documents(origin, documents);
            database.execute("update origin set complete = 1 where id = ?", origin);
        });
    }

    @Override
    public synchronized void publishIncremental(
            String kind, String location, String stamp, Map<String, TypeInfo> types) {
        database.transaction(() -> {
            var existing = id(kind, location);
            var origin = existing.orElseGet(() -> {
                database.execute("insert into origin (kind, location, stamp) values (?, ?, ?)", kind, location, stamp);
                return database.lastId();
            });
            if (existing.isPresent() && !storedStamp(origin).equals(stamp)) {
                database.execute("delete from type where origin = ?", origin);
                database.execute("delete from document where origin = ?", origin);
                database.execute("update origin set stamp = ?, complete = 0 where id = ?", stamp, origin);
            }
            try (var sink = new Sink()) {
                types.forEach((name, type) -> sink.put(origin, name, type));
            }
        });
    }

    private String storedStamp(long origin) {
        try (var rows = database.query("select stamp from origin where id = ?", origin)) {
            return rows.next() ? rows.text(0) : "";
        }
    }

    private Optional<Long> id(String kind, String location) {
        try (var rows = database.query(
                "select id from origin where kind = ? and location = ?", kind, location)) {
            return rows.next() ? Optional.of(rows.integer(0)) : Optional.empty();
        }
    }

    private void documents(long origin, List<Document> documents) {
        if (documents.isEmpty()) return;
        try (var sink = Statement.of(database,
                "insert into document (origin, package, kind, slug, title, body, source)"
                        + " values (?, ?, ?, ?, ?, ?, ?)")) {
            for (var document : documents) {
                sink.run(origin, document.packageName(), document.kind(), document.slug(), document.title(),
                        document.body(), document.source());
            }
        }
    }

    @Override
    public synchronized void close() {
        database.close();
    }

    /// The statements one bulk write needs, prepared once and run for every row
    /// — which for the JDK's `String` alone is several hundred of them.
    private final class Sink implements AutoCloseable {

        private final Statement forget = Statement.of(database, "delete from type where origin = ? and name = ?");
        private final Statement type = Statement.of(database,
                "insert into type (origin, name, kind, modifiers, superclass, doc, source, line)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?)");
        private final Statement parameter = Statement.of(database,
                "insert into type_parameter (type, position, name) values (?, ?, ?)");
        private final Statement related = Statement.of(database,
                "insert into related (type, relation, position, name) values (?, ?, ?, ?)");
        private final Statement member = Statement.of(database,
                "insert into member (type, position, kind, name, returns, modifiers, doc, line)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?)");
        private final Statement argument = Statement.of(database,
                "insert into parameter (member, position, type, name) values (?, ?, ?, ?)");
        private final Statement tag = Statement.of(database,
                "insert into tag (type, member, position, tag, name, text) values (?, ?, ?, ?, ?, ?)");

        private void put(long origin, String name, TypeInfo info) {
            forget.run(origin, name);
            var id = type.run(origin, name, info.kind().name(), String.join(" ", info.modifiers()),
                    info.superclass(), info.doc(), info.source(), info.line());

            for (var at = 0; at < info.typeParameters().size(); at++) {
                parameter.run(id, at, info.typeParameters().get(at));
            }
            related(id, "implements", info.interfaces());
            related(id, "permits", info.permits());
            related(id, "nested", info.nested());
            tags(id, null, info.tags());

            var position = 0;
            for (var method : info.methods()) {
                var owner = member.run(id, position++, "method", method.name(), method.returns(),
                        String.join(" ", method.modifiers()), method.doc(), method.line());
                for (var at = 0; at < method.parameters().size(); at++) {
                    var taken = method.parameters().get(at);
                    argument.run(owner, at, taken.type(), taken.name());
                }
                tags(null, owner, method.tags());
            }
            for (var field : info.fields()) {
                var owner = member.run(id, position++, "field", field.name(), field.type(),
                        String.join(" ", field.modifiers()), field.doc(), field.line());
                tags(null, owner, field.tags());
            }
        }

        private void related(long type_, String relation, List<String> names) {
            for (var at = 0; at < names.size(); at++) related.run(type_, relation, at, names.get(at));
        }

        private void tags(Long type_, Long member_, List<TypeInfo.Tag> tags) {
            for (var at = 0; at < tags.size(); at++) {
                var written = tags.get(at);
                tag.run(type_, member_, at, written.tag(), written.name(), written.text());
            }
        }

        @Override
        public void close() {
            forget.close();
            type.close();
            parameter.close();
            related.close();
            member.close();
            argument.close();
            tag.close();
        }
    }

    private List<TypeInfo.Method> methods(long type) {
        var parameters = parameters(type);
        var tags = tags(type, "method");
        var methods = new ArrayList<TypeInfo.Method>();
        try (var rows = database.query(
                "select id, name, returns, modifiers, doc, line from member where type = ? and kind = 'method'"
                        + " order by position",
                type)) {
            while (rows.next()) {
                var id = rows.integer(0);
                methods.add(new TypeInfo.Method(rows.text(1), rows.text(2),
                        parameters.getOrDefault(id, List.of()), words(rows.text(3)), rows.text(4),
                        tags.getOrDefault(id, List.of()), (int) rows.integer(5)));
            }
        }
        return List.copyOf(methods);
    }

    private List<TypeInfo.Field> fields(long type) {
        var tags = tags(type, "field");
        var fields = new ArrayList<TypeInfo.Field>();
        try (var rows = database.query(
                "select id, name, returns, modifiers, doc, line from member where type = ? and kind = 'field'"
                        + " order by position",
                type)) {
            while (rows.next()) {
                var id = rows.integer(0);
                fields.add(new TypeInfo.Field(rows.text(1), rows.text(2), words(rows.text(3)), rows.text(4),
                        tags.getOrDefault(id, List.of()), (int) rows.integer(5)));
            }
        }
        return List.copyOf(fields);
    }

    /// Every parameter of every member of a type, in one query rather than one
    /// per member.
    private Map<Long, List<TypeInfo.Parameter>> parameters(long type) {
        var found = new LinkedHashMap<Long, List<TypeInfo.Parameter>>();
        try (var rows = database.query("""
                select parameter.member, parameter.type, parameter.name
                from parameter join member on member.id = parameter.member
                where member.type = ?
                order by parameter.member, parameter.position
                """, type)) {
            while (rows.next()) {
                found.computeIfAbsent(rows.integer(0), member -> new ArrayList<>())
                        .add(new TypeInfo.Parameter(rows.text(1), rows.text(2)));
            }
        }
        return found;
    }

    private Map<Long, List<TypeInfo.Tag>> tags(long type, String kind) {
        var found = new LinkedHashMap<Long, List<TypeInfo.Tag>>();
        try (var rows = database.query("""
                select tag.member, tag.tag, tag.name, tag.text
                from tag join member on member.id = tag.member
                where member.type = ? and member.kind = ?
                order by tag.member, tag.position
                """, type, kind)) {
            while (rows.next()) {
                found.computeIfAbsent(rows.integer(0), member -> new ArrayList<>())
                        .add(new TypeInfo.Tag(rows.text(1), rows.text(2), rows.text(3)));
            }
        }
        return found;
    }

    private List<TypeInfo.Tag> tags(String sql, long id) {
        var tags = new ArrayList<TypeInfo.Tag>();
        try (var rows = database.query(sql, id)) {
            while (rows.next()) tags.add(new TypeInfo.Tag(rows.text(1), rows.text(2), rows.text(3)));
        }
        return List.copyOf(tags);
    }

    private List<String> strings(String sql, Object... parameters) {
        var values = new ArrayList<String>();
        try (var rows = database.query(sql, parameters)) {
            while (rows.next()) values.add(rows.text(0));
        }
        return List.copyOf(values);
    }

    /// Modifiers are a list of words, and a list of words is a string. A table
    /// for them would say nothing a space does not.
    private static List<String> words(String text) {
        return text.isEmpty() ? List.of() : List.of(text.split(" "));
    }
}
