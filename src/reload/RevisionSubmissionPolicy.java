package reload;

import web.Request;

/// Decides whether a request may submit a revision.
///
/// The reload package does not assume an authentication mechanism. The host
/// supplies this policy, usually after authentication middleware has put a
/// principal in [Request#attributes()]. A policy that returns `false` causes
/// the HTTP source to answer `403`; a policy is never inferred from a missing
/// header or a client supplied field.
@FunctionalInterface
public interface RevisionSubmissionPolicy {

    /// Answers whether this request may submit a revision.
    boolean allow(Request request);
}
