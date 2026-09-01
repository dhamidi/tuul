# identity

`identity` is a proposed package for authenticated subjects in Tuul. It is a
design document. It is not implemented, and no type or message name in these
documents is a public API.

The package separates authentication from the local session:

1. An authentication provider proves an external or local identity.
2. A binding registry resolves that provider identity to one local subject.
3. The subject actor decides whether the subject may start a session.
4. Identity returns the subject and bounded provider claims to the application.
5. `web.sessions` carries only the subject and its session generation.

The same subject can bind several authentication methods. A password is one
method. An OpenID Connect account is another. Google is an OpenID Connect
configuration, not a special subject type.

The package is responsible for:

- durable subjects and session generation;
- unique authentication bindings;
- provider-neutral verified assertions;
- provider-neutral authentication results with normalized profile claims;
- one-use login transactions for redirect providers;
- local password hashing, recovery, and throttling;
- generic OpenID Connect discovery, redirects, code exchange, and ID-token
  validation;
- explicit account creation, linking, unlinking, and revocation decisions;
- the bridge from a verified subject to `web.sessions.Session`.

The application still owns its `User` or profile, authorization rules, page
text, post-login destination, and account-creation policy. A provider adapter
owns its protocol. It must return a verified assertion before identity code can
use a provider account.

After authentication, the application success handler receives the local
subject and a snapshot of selected provider claims. It can send an idempotent
command to its profile actor. Identity does not store those claims or put them
in the session cookie.

The package does not treat an email claim as a provider identity. OpenID
Connect binds an account by issuer and subject. An application may use a
verified email claim during an explicit account-creation or linking policy.

This design uses the JDK security APIs, `fetch`, and `json`. It does not depend
on an OAuth or JWT library. The implementation must therefore enforce an
algorithm allowlist, bounds, exact issuer and redirect matching, token expiry,
audience, nonce, state, and PKCE requirements itself.

## Documents

| You want | Read |
|---|---|
| Add password and Google login to one application | [tutorial.md](tutorial.md) |
| Complete one provider, linking, session, or recovery task | [howto.md](howto.md) |
| Read the proposed actors, values, and protocols | [reference.md](reference.md) |
| Understand why the boundaries have this shape | [explanation.md](explanation.md) |

Read the reference before implementing the package.
