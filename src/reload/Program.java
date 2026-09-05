package reload;

/// Defines one complete application generation.
@FunctionalInterface
public interface Program {

    /// Loads the single root [Program] provider and defines its generation.
    /// This is the usual definition passed to [RevisionCompiler].
    static Generation generation(CandidateContext candidate) throws Exception {
        var services = Generation.services(candidate, Program.class);
        var programs = services.services(Program.class);
        if (programs.size() != 1) {
            try { services.close(); } catch (Exception ignored) {}
            throw new IllegalStateException("root module " + candidate.root().getName()
                    + " must provide exactly one reload.Program, found " + programs.size()
                    + " " + candidate.declarations(Program.class));
        }
        try {
            return Generation.merge(services, programs.getFirst().define());
        } catch (Exception | Error failure) {
            try { services.close(); } catch (Exception close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    /// Builds the values that must change together. The coordinator calls this
    /// once per candidate. A throw rejects the candidate.
    Generation define() throws Exception;
}
