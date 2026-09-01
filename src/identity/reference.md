# Reference: proposed identity contract

This page is the proposed implementation contract for `identity`. No public
implementation exists. The names and message shapes are design vocabulary, not
current Java APIs.

## Scope

The package defines local subjects, authentication providers, verified
assertions, authentication results, authentication bindings, redirect login
transactions, local password credentials and recovery, session-generation
revocation, and explicit binding workflows.

The package includes a generic OpenID Connect provider. A conforming OpenID
Connect service is configuration. A different protocol implements the provider
contract.

The package does not define an application `User`, profile, role, permission,
authorization policy, provider account, SMTP client, or browser UI. It does not
store OAuth access or refresh tokens as part of login-only authentication.

## Terms

| Term | Meaning |
|---|---|
| subject | An opaque stable local identifier for an authenticated principal. |
| provider | A named implementation that proves one local or external identity. |
| authority | The validated namespace in which a provider's external subject is unique. For OpenID Connect this is normally the exact issuer. A provider with an explicit accepted-issuer set uses one configured canonical authority. |
| external subject | The stable provider identifier. For OpenID Connect this is `sub`. |
| binding key | The tuple `(provider, authority, external subject)`. |
| binding | The unique ownership relation from one binding key to one local subject. |
| assertion | A provider result whose protocol proof has already been validated. |
| claim | Bounded provider data that is not part of the binding key. |
| `AuthenticationResult` | The local subject and claim snapshot returned after identity resolves and accepts an assertion. |
| completion ID | A stable idempotency key for one successful authentication attempt. |
| login transaction | One short-lived, one-use provider exchange bound to one browser flow. |
| session generation | A monotonically increasing subject value that invalidates older local sessions. |

## Required separation

Authentication providers prove identities. They do not create local subjects,
link accounts, write sessions, or decide authorization.

Identity core resolves a verified assertion to a local subject. It runs the
application's explicit decision when no binding exists. It writes a local
session only after the subject actor and application success handler accept the
result.

`web.sessions` carries an authenticated local subject after authentication. It
does not parse provider responses, validate tokens, refresh provider tokens, or
link provider accounts. It does not carry profile claims.

## Actor topology

| Address pattern | Spawn | State authority |
|---|---|---|
| `identity/subject/{subject}` | durable | Status, session generation, binding references, local password credential, and recovery token digests. |
| `identity/bindings/{shard}` | durable | Binding key to subject ownership and optional tombstones. |
| `identity/link/{request}` | durable | One account-create, link, or unlink workflow and its accepted steps. |
| `identity/throttle/{key-or-shard}` | durable | Bounded local-password reservations, failures, and lock times. |
| `identity/login/{request}` | ephemeral by default | One redirect transaction and its transient protocol secrets. |

The binding shard function must be deterministic. It must map one complete
binding key to one actor for as long as the binding exists.

The subject and binding registry do not share a transaction. The link actor
uses a stable request ID and idempotent commands. It resumes incomplete steps
after replay. It must tolerate a repeated result and a crash between steps.

The default login actor keeps transient secrets in memory and does not
passivate before its expiry. A process restart invalidates the transaction. A
deployment may instead use an ephemeral actor that owns an encrypted external
transaction store. The actor log and external store must not both claim to own
the same transaction state.

## Provider contract

A provider has one stable application name. Renaming a provider changes its
binding keys and requires an explicit migration.

A redirect provider supports two operations:

1. `start` creates a bounded challenge and one login transaction.
2. `complete` consumes the transaction and returns an assertion or a generic
   authentication failure.

A direct provider, such as local password authentication, may complete without
a browser redirect. It must still return the same assertion shape.

A provider owns:

- protocol request construction;
- protocol-specific transaction values;
- callback parsing and bounds;
- remote calls and their timeouts;
- cryptographic and protocol proof validation;
- selection and normalization of bounded claims;
- provider-specific error redaction.

A provider must not return an assertion before proof validation finishes. It
must not put a client secret, authorization code, access token, refresh token,
raw password, nonce, state secret, or PKCE verifier in the assertion.

## Verified assertion

An assertion contains:

```json
{
  "provider": "google",
  "authority": "https://accounts.google.com",
  "externalSubject": "provider-stable-subject",
  "authenticatedAt": 1735689600000,
  "claims": {
    "email": "person@example.test",
    "emailVerified": true
  }
}
```

`provider`, `authority`, and `externalSubject` form the binding key. They must
be non-empty and bounded. A provider must derive them only from validated
protocol data and explicit configuration. It must not derive the authority by
loosely normalizing untrusted input.

`authenticatedAt` is optional when the protocol does not prove it. Claims are
optional. The provider must cap claim names, string lengths, array sizes, and
the complete assertion size.

An assertion is evidence for authentication. It is not application
authorization. It is not permission to create or link a local subject.

## Common claims

A provider maps common presentation fields to these optional claim names:

| Claim | Meaning |
|---|---|
| `username` | The provider's current human-readable account handle. |
| `displayName` | The provider's current display name. |
| `email` | One provider-selected email address. |
| `emailVerified` | Whether the provider reports the accompanying email address as verified. |
| `pictureUrl` | The provider's current profile-picture URL. |

The provider omits a claim when it cannot prove or select a bounded value. It
must omit `emailVerified` when it does not return `email`. Absence means
unknown. It does not mean false.

An adapter can return configured provider-specific claims. It prefixes each
nonstandard name with the stable provider name. The application must not use a
presentation claim as a binding key or authorization fact.

Google maps `name`, `email`, `email_verified`, and `picture` to the common
names. A GitHub adapter maps the [authenticated-user response][github-user]
fields `login`, `name`, and `avatar_url` to `username`, `displayName`, and
`pictureUrl`. It returns `emailVerified` only when the [GitHub email
response][github-email] proves it.

## Authentication result

Identity creates an `AuthenticationResult` after it resolves the binding and
the subject actor accepts authentication:

```json
{
  "completionId": "one-use-authentication-id",
  "subject": "opaque-application-subject",
  "sessionGeneration": 4,
  "provider": "google",
  "authority": "https://accounts.google.com",
  "authenticatedAt": 1735689600000,
  "claims": {
    "displayName": "Ada Example",
    "email": "ada@example.test",
    "emailVerified": true,
    "pictureUrl": "https://provider.example/avatar"
  }
}
```

`completionId` identifies one successful authentication attempt. A redirect
flow reuses its login-transaction request ID. A direct flow creates the ID at
the request boundary. A retry of the same completion reuses the ID.

`authenticatedAt` is absent when the provider did not prove an authentication
time. `claims` is an immutable bounded snapshot from this authentication. The
result contains no authorization code, provider token, raw provider response,
or application authorization.

Identity passes the result to the configured application success handler. The
handler can send an idempotent command to `profile/{subject}`. The handler must
use `completionId` when it makes a durable change. Identity runs the handler
under a configured deadline.

The handler accepts or refuses the login. Identity writes the local session
only after the handler accepts. A missing handler accepts and discards the
claims by default.

A crash can cause the handler to receive the same completion again. The
profile actor returns its prior result for a repeated `completionId`. Identity
does not promise exactly-once delivery across a process failure.

Identity does not store the result after completion. The application profile
actor owns any durable display name, username, email, or picture choice.

## Binding ownership

The binding registry serializes these outcomes:

| Outcome | Meaning |
|---|---|
| `claimed` | The binding key now belongs to the requested subject. |
| `same-owner` | The binding already belongs to that subject. |
| `already-claimed` | Another subject owns the binding. |
| `released` | Policy allowed the active ownership to be removed. |
| `tombstoned` | Policy forbids reassignment. |

The registry key uses the complete provider name, authority, and external
subject. Equal external-subject strings from different providers or issuers do
not collide.

The subject actor stores binding references. The registry remains the
authority for uniqueness. A subject reference alone does not prove ownership.

## Unknown binding decision

When a verified assertion has no binding, identity core calls one explicit
application decision. The decision returns one of:

- refuse;
- create a subject;
- require an invitation;
- require an authenticated subject to confirm a link.

The default is `refuse`. The decision receives bounded verified claims and the
provider name. It does not receive provider tokens or unvalidated callback
parameters.

Identity core must not automatically link by email, display name, domain, or
another claim. A verified email claim may inform an explicit application
decision. It does not replace the provider binding key.

## Durable link workflow

A create or link workflow records:

- request ID;
- operation;
- binding key;
- target subject when known;
- binding-claim result;
- subject-update result;
- terminal result.

The workflow claims the registry binding before it adds the subject reference.
If the second step fails, it retries or applies the configured compensation.
Every command includes the request ID. Repeating a completed command returns
the prior result.

An unlink workflow first prevents the subject from authenticating with the
binding. It then releases or tombstones the registry key according to policy.
It must not remove the last permitted authentication method unless the
application explicitly disables the subject.

## Login transaction

A redirect login transaction records in transient state:

- request ID;
- provider name;
- operation (`login` or `link`);
- target subject for a link;
- exact callback URL;
- allowed post-login destination identifier;
- state proof;
- nonce when required;
- PKCE verifier when required;
- absolute expiry;
- consumed status.

The callback consumes the transaction once. A wrong provider, state, callback
URL, target subject, expiry, or consumed status returns a generic failure.

The public state value contains a bounded request selector and an unpredictable
proof. The callback uses the selector to address the login actor directly. The
actor compares the proof before it releases the nonce or PKCE verifier. The
state value must not contain the nonce, PKCE verifier, return URL, or provider
secret.

The transaction must not accept an arbitrary post-login URL from a callback
parameter. The application chooses a named or allowlisted destination before
the provider redirect.

## Local password provider

The password binding key is:

```text
(password, <identifier namespace>, <normalized identifier>)
```

One configured normalization policy must handle sign-up, lookup, throttling,
and recovery. The default proposal applies `String.strip()`, Unicode NFKC, and
`toLowerCase(Locale.ROOT)`. It rejects null, empty, controls, and values over
the code-point limit.

The stored password format contains version, JCA algorithm, work factor, salt,
and derived value. The first proposed algorithm is
`PBKDF2WithHmacSHA256`. Startup rejects an unavailable algorithm. It never
downgrades silently.

Every credential uses a fresh random salt. The derived value contains at least
256 bits. Verification compares derived bytes with `MessageDigest.isEqual`.
The implementation bounds password size and concurrent password work.

The request boundary asks the subject actor for stored verification material.
It sends no raw password. It performs derivation and comparison outside actor
messages. Unknown bindings use a configured dummy hash and the same bounded
work path.

The password provider returns an assertion with provider `password`, the
identifier namespace as authority, and the normalized identifier as external
subject. Identity core then uses the same binding resolution and session path
as any other provider.

## Local recovery

The subject actor owns password-recovery and local-verification token digests.
A token has a public request ID and a separate 32-byte random secret. Actor
state stores `SHA-256(secret)`, purpose, expiry, and consumed or revoked status.

An external effect prepares the secret and keeps it in a short-lived delivery
outbox. The subject records the digest before it requests delivery. Both
effects are idempotent by request ID.

One subject command checks the digest, purpose, expiry, and status. A password
reset consumes the token, stores the new hash, and increments session
generation in the same update.

## OpenID Connect provider

An OpenID Connect configuration contains:

- stable provider name;
- exact issuer;
- discovery document URI or explicit endpoints;
- client ID;
- runtime client-secret reference when required;
- exact callback URL;
- scopes;
- allowed ID-token signature algorithms;
- HTTP, document, token, clock-skew, and cache bounds;
- selected claim policy.

The provider uses the authorization code flow. It generates a PKCE verifier
and sends its S256 challenge unless a documented provider incompatibility
prevents it. It uses one-time state and nonce values bound to the login
transaction.

The callback rejects an OAuth error or malformed response with a generic
public failure. It checks the state before it uses the authorization code. It
exchanges the code only at the configured token endpoint with the exact saved
callback URL and PKCE verifier.

Before returning an assertion, the provider validates:

- JWT structure and bounded decoded sizes;
- an allowed `alg` that is not `none`;
- signature against a key selected from the configured issuer's bounded key
  set;
- exact issuer;
- audience containing the configured client ID;
- authorized party when the token has multiple audiences or the claim is
  otherwise required;
- expiry and issued-at under configured clock skew;
- nonce equality with the consumed transaction;
- non-empty bounded `sub`.

The provider may refresh discovery or keys once when a valid key ID is unknown.
It must bound cache lifetime, response size, redirects, and concurrent refresh.
It must not select an algorithm from the token without checking the configured
allowlist.

The assertion binding key is `(provider name, authority, validated sub)`. The
authority is the exact validated issuer unless the provider configuration
defines an explicit accepted-issuer set and one canonical authority. `email`,
`email_verified`, `name`, `picture`, and hosted-domain values are source
claims. The provider maps selected presentation values to common claim names.
They are not binding keys.

## Google configuration

Google uses the generic OpenID Connect provider with:

- provider name `google`;
- exact accepted issuer values `https://accounts.google.com` and
  `accounts.google.com`;
- canonical binding authority `https://accounts.google.com`;
- Google's discovery document;
- an application client ID and secret;
- an exact registered callback URL;
- required `openid` scope and optional `profile` and `email` scopes.

The implementation must compare the token issuer against that explicit set.
It must not apply general URL normalization to an untrusted issuer. It stores
the canonical binding authority in every Google binding key. It always uses
validated `sub` as the external subject.

Google documents both accepted issuer values and the stable `sub` requirement
in [Sign in with Google OpenID Connect][google-oidc].

## Session bridge

Every accepted authentication finishes with the same local session payload:

```json
{
  "subject": "opaque-application-subject",
  "sessionGeneration": 4
}
```

`web.sessions` signs but does not encrypt this value. The bridge requires
session middleware to run first. It asks the subject actor for current status
and generation under a bounded deadline. It continues only when the subject is
active and the generations match.

The bridge does not copy claims from the authentication result. The
application loads its profile by subject when it needs friendly display data.

Incrementing session generation revokes all local sessions for the subject.
Clearing one cookie removes only that browser copy.

A local session does not automatically follow provider revocation, provider
logout, or provider account disablement. A provider adapter may validate a
signed provider event and request a local generation change. Without such an
event or an online check, the local session remains valid until its own expiry
or revocation.

## Provider tokens

Login-only authentication discards access and refresh tokens after it extracts
and validates the required identity proof. Identity actors do not store them.

An application that calls provider APIs installs a separate token-owning
service. That service owns encryption, scopes, refresh serialization,
revocation, provider errors, and deletion. A provider token must not enter a
subject log, local session cookie, assertion, inspection document, or ordinary
log.

## Web integration

An installed provider contributes its start and completion handlers through a
`web.Feature`. The application chooses the mount, route names, login page,
success handler, failure handler, and allowed return destinations.

The success handler receives the complete authentication result. It finishes
required application profile work before it accepts. The session bridge writes
the cookie after that acceptance.

Password forms use `web.forms` and ordinary `web.sessions.Csrf`. Redirect
provider callbacks use login-transaction state, nonce, and PKCE. Form CSRF does
not replace those protocol checks.

The identity session bridge composes after `web.sessions` middleware.
Authorization remains application middleware or an application actor decision.

## Failure rules

| Situation | Required behavior |
|---|---|
| Provider returns unvalidated data | Do not construct an assertion. |
| Binding is absent | Run the explicit unknown-binding decision. Default to refusal. |
| Binding belongs to another subject during link | Refuse without changing either subject. |
| Email matches an existing local identifier | Do not link automatically. |
| Login transaction is missing, expired, wrong, or consumed | Return a generic provider failure. |
| Issuer, audience, signature, algorithm, nonce, or expiry is wrong | Return a generic provider failure and create no assertion. |
| Provider key ID is unknown after one bounded refresh | Return a generic provider failure. |
| Password binding is unknown | Run dummy-hash work and return the generic credential failure. |
| Subject is inactive or session generation is stale | Reject and clear the local session. |
| Application success handler refuses, throws, or exceeds its deadline | Return a generic login failure and do not write a session. |
| Provider account is later disabled | Keep the local session unless configured event or online policy revokes it. |
| Input or response exceeds a bound | Refuse before expensive parsing or cryptography. |

Security failures may produce redacted operational events. They must not carry
authorization codes, tokens, passwords, assertions with unnecessary claims,
client secrets, PKCE verifiers, state proofs, or nonce values.

## Protocol references

Implement the provider against [OpenID Connect Core][oidc-core] and [OpenID
Connect Discovery][oidc-discovery]. Apply the current OAuth security practices
in [RFC 9700][oauth-security].

[google-oidc]: https://developers.google.com/identity/openid-connect/openid-connect
[github-email]: https://docs.github.com/en/rest/users/emails#list-email-addresses-for-the-authenticated-user
[github-user]: https://docs.github.com/en/rest/users/users#get-the-authenticated-user
[oauth-security]: https://www.rfc-editor.org/rfc/rfc9700.html
[oidc-core]: https://openid.net/specs/openid-connect-core-1_0.html
[oidc-discovery]: https://openid.net/specs/openid-connect-discovery-1_0.html
