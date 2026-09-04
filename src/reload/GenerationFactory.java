package reload;

/// Creates a generation from a resolved candidate module layer.
///
/// The factory chooses the entrypoint explicitly. The compiler does not
/// inspect class names, instantiate application classes, or discover a
/// [Program].
@FunctionalInterface
public interface GenerationFactory {

    /// Builds one generation from the candidate module layer.
    /// The factory owns any resources that the returned generation must close.
    Generation define(ModuleLayer layer) throws Exception;
}
