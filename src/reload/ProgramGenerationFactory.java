package reload;

import java.util.ServiceLoader;

/// Creates a generation from the single [Program] provider in a module layer.
public final class ProgramGenerationFactory implements GenerationFactory {

    /// Loads exactly one [Program] provider from the candidate layer.
    @Override
    public Generation define(ModuleLayer layer) throws Exception {
        var programs = ServiceLoader.load(layer, Program.class).stream()
                .map(ServiceLoader.Provider::get).toList();
        if (programs.size() != 1) throw new IllegalStateException(
                "candidate layer must provide exactly one reload.Program, found " + programs.size());
        return programs.getFirst().define();
    }
}
