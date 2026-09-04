package reload;

/// Creates a generation from the single [Program] provider in the candidate root.
public final class ProgramGenerationFactory implements GenerationFactory {

    /// Loads exactly one root provider and defines its generation.
    @Override
    public Generation define(CandidateContext candidate) throws Exception {
        var services = new ServiceGenerationFactory(Program.class).define(candidate);
        var programs = services.services(Program.class);
        if (programs.size() != 1) {
            try { services.close(); } catch (Exception ignored) {}
            throw new IllegalStateException("root module " + candidate.root().getName()
                    + " must provide exactly one reload.Program, found " + programs.size()
                    + " " + candidate.declarations(Program.class));
        }
        try {
            return Generation.merge(java.util.List.of(services, programs.getFirst().define()));
        } catch (Exception | Error failure) {
            try { services.close(); } catch (Exception close) { failure.addSuppressed(close); }
            throw failure;
        }
    }
}
