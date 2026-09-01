/// Proposed provider-neutral authentication and local subject services.
///
/// This package is design-only. It has no implementation or public types. Read
/// [the tutorial](tutorial.md), [the how-to](howto.md), [the
/// reference](reference.md), and [the explanation](explanation.md).
///
/// An authentication provider returns a verified assertion. A durable binding
/// registry resolves the assertion to one local subject. The subject actor
/// decides whether that subject may start a session.
///
/// A successful authentication returns an `AuthenticationResult`. It contains
/// the subject, session generation, provider, authority, authentication time,
/// and bounded claims. The application success handler can send those claims
/// to its profile actor.
/// The handler uses the completion ID to make that command idempotent.
///
/// [web.sessions] carries only the subject and session generation. Identity
/// does not store profile claims.
///
/// Password and OpenID Connect authentication use the same binding and session
/// contracts. Google is one OpenID Connect configuration. A custom provider
/// implements the provider contract and returns the same verified assertion.
///
/// The package does not define an application user, profile, role, permission,
/// or authorization policy. It does not automatically link accounts by email.
/// It does not store provider access or refresh tokens unless an application
/// installs a separate token-owning service.
package identity;
