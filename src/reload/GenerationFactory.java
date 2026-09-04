package reload;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Creates a generation from one compiled candidate module graph.
///
/// The factory receives a [CandidateContext] with the exact root module.
/// The compiler does not inspect class names or instantiate application
/// classes outside the factory.
@FunctionalInterface
public interface GenerationFactory {

    /// Builds one generation from the candidate context.
    /// The factory owns any resources that the returned generation must close.
    Generation define(CandidateContext candidate) throws Exception;

    /// Combines factories in argument order and closes partial generations on failure.
    static GenerationFactory compose(List<GenerationFactory> factories) {
        Objects.requireNonNull(factories, "factories");
        var copy = factories.stream().map(Objects::requireNonNull).toList();
        return candidate -> {
            var generations = new ArrayList<Generation>();
            try {
                for (var factory : copy) generations.add(Objects.requireNonNull(
                        factory.define(candidate), "generation factory returned null"));
                return Generation.merge(generations);
            } catch (Exception | Error failure) {
                var close = Generation.closeAll(generations);
                if (close != null) failure.addSuppressed(close);
                throw failure;
            }
        };
    }

    /// Combines factories in argument order.
    static GenerationFactory compose(GenerationFactory first, GenerationFactory... rest) {
        Objects.requireNonNull(first, "first");
        var all = new ArrayList<GenerationFactory>();
        all.add(first);
        if (rest != null) for (var factory : rest) all.add(factory);
        return compose(all);
    }
}
