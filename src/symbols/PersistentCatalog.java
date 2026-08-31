package symbols;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// A catalog over the last complete rows in `index.db`.
///
/// It knows no source paths, compiler, vendor directory, or runtime image. A
/// missing database is an indexing state, and a database that appears after
/// this object is created is opened on the next operation.
final class PersistentCatalog implements Catalog {

    private final Path file;
    private Store store;
    private boolean closed;

    PersistentCatalog(Path file) {
        this.file = file;
    }

    @Override
    public synchronized boolean ready() {
        var kept = store();
        return kept.isPresent() && kept.get().complete("project", "sources").isPresent();
    }

    @Override
    public synchronized Optional<TypeInfo> lookup(String name) {
        var kept = store();
        if (kept.isEmpty()) return Optional.empty();
        for (var origin : origins(kept.get())) {
            for (var candidate : candidates(name)) {
                var found = kept.get().type(origin, candidate);
                if (found.isPresent()) return found;
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized Optional<Document> document(String packageName, String kind, String slug) {
        return documentPage(packageName, kind, slug).selected();
    }

    @Override
    public synchronized List<Document> documents(String packageName) {
        return documents(packageName, "");
    }

    @Override
    public synchronized List<Document> documents(String packageName, String kind) {
        var kept = store();
        if (kept.isEmpty()) return List.of();
        return kept.get().complete("project", "documents")
                .map(origin -> kept.get().documents(origin, packageName, kind))
                .orElse(List.of());
    }

    @Override
    public synchronized DocumentPage documentPage(String packageName, String kind, String slug) {
        var documents = documents(packageName);
        return new DocumentPage(
                documents.stream().filter(document -> document.kind().equals(kind) && document.slug().equals(slug))
                        .findFirst(), documents);
    }

    @Override
    public synchronized List<String> names() {
        var kept = store();
        if (kept.isEmpty()) return List.of();
        return kept.get().complete("project", "sources").map(kept.get()::names).orElse(List.of());
    }

    @Override
    public synchronized List<Root> roots() {
        return store().map(Store::roots).orElse(List.of());
    }

    @Override
    public synchronized List<Match> search(String text, int limit) {
        return store().map(kept -> kept.search(text, limit)).orElse(List.of());
    }

    private List<Long> origins(Store kept) {
        var found = new ArrayList<Long>();
        kept.complete("project", "sources").ifPresent(found::add);
        kept.stored("vendor", "vendor").ifPresent(found::add);
        kept.stored("platform", System.getProperty("java.home")).ifPresent(found::add);
        kept.stored("platform", Index.platformNavigationLocation()).ifPresent(found::add);
        return found;
    }

    private Optional<Store> store() {
        if (closed) return Optional.empty();
        if (store == null) store = Store.read(file).orElse(null);
        return Optional.ofNullable(store);
    }

    private static List<String> candidates(String name) {
        var candidates = new ArrayList<String>();
        candidates.add(name);
        var binary = name.toCharArray();
        for (var at = binary.length - 1; at >= 0; at--) {
            if (binary[at] != '.') continue;
            binary[at] = '$';
            candidates.add(new String(binary));
        }
        return candidates;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (store != null) store.close();
        store = null;
    }
}
