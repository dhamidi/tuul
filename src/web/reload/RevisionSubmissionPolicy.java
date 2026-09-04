package web.reload;

import web.Request;

/// Decides whether a request may submit a revision.
///
/// The host supplies this policy after authentication middleware evaluates a
/// request. A policy that returns `false` causes the HTTP source to answer
/// `403`. The source never infers a policy from a missing header or client field.
@FunctionalInterface
public interface RevisionSubmissionPolicy {

    /// Allows or refuses one revision request. Return `false` to refuse it.
    boolean allow(Request request);
}
