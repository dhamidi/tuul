# How-to

This page describes tasks for the proposed `identity` package. It does not
document current Java APIs. Exact rules are in [reference.md](reference.md).
Design reasons are in [explanation.md](explanation.md).

## Add an OpenID Connect provider

1. Give the provider configuration a stable application name.
2. Configure the exact issuer and discovery document.
3. Configure the client ID and a runtime client-secret reference.
4. Register one exact callback URL with the provider.
5. Select only the required scopes.
6. Allow only the expected ID-token signature algorithms.
7. Configure bounded HTTP, document-size, token-size, and key-cache limits.
8. Install the provider's web feature at an application-chosen mount point.
9. Configure the decision for a verified but unbound provider account.

The provider name becomes part of every binding key. Do not rename it after
bindings exist without an explicit migration command.

## Configure Google login

1. Use OpenID Connect provider name `google`.
2. Accept only the exact issuer values `https://accounts.google.com` and
   `accounts.google.com` that Google documents.
3. Use `https://accounts.google.com` as the canonical binding authority.
4. Request `openid`. Add `profile` and `email` only when the application uses
   those claims.
5. Use the configured client ID as the required ID-token audience.
6. Resolve the binding from the canonical authority and validated `sub` claim.
7. Map `name` to `displayName` and `picture` to `pictureUrl`.
8. Map `email` and `email_verified` to `email` and `emailVerified`.
9. Treat those values as claims, not as the account key.
10. Keep provider API access tokens outside the login-only identity state.

Google-specific behavior stops at configuration and claims. The generic OpenID
Connect provider owns the redirect, exchange, and validation protocol.

## Add another OpenID Connect provider

Create another provider configuration. Give it a different stable name,
issuer, client credentials, callback URL, scopes, algorithms, and claim policy.
The binding registry keeps the providers separate even when they return the
same `sub` text.

Use distinct callback routes or verify the response issuer before selecting a
provider. A multi-provider callback must reject an issuer mix-up.

## Implement a non-OpenID provider

1. Implement the provider start operation.
2. Declare the transaction values and secrets the provider requires.
3. Implement the callback or credential completion operation.
4. Validate every protocol proof inside the provider adapter.
5. Return a verified assertion with a stable provider name, authority, and
   external subject.
6. Map common provider fields to `username`, `displayName`, `email`,
   `emailVerified`, and `pictureUrl`.
7. Return bounded selected claims separately from the binding key.
8. Define provider-specific logout and token behavior explicitly.
9. Add conformance tests for replay, mix-up, expiry, wrong audience, wrong
   authority, malformed input, and key rotation where applicable.

Identity core must not parse an unverified provider response. The adapter must
not return an assertion when proof validation is incomplete.

## Store a redirect login transaction

1. Create a random request ID.
2. Generate a random state proof, nonce, and PKCE verifier outside an actor
   update.
3. Derive the PKCE challenge from the verifier with S256.
4. Encode the request ID and state proof in one bounded opaque state value.
5. Store the state proof, nonce, and verifier in one ephemeral login actor with
   provider, return location, and
   absolute expiry.
6. Send only the opaque state value and PKCE challenge to the provider.
7. Use the returned request ID to address the login actor directly.
8. Consume the transaction once at the callback.
9. Reject an unknown, expired, mismatched, or already consumed transaction.
10. Evict the actor after success or terminal failure.

The default transaction does not survive a process restart. The person starts
the login again. A deployment that requires restart survival can use an
actor-owned encrypted store. A signed but readable `web.sessions` cookie must
not contain a client secret, nonce, or PKCE verifier.

## Create a new subject from an assertion

1. Receive a verified assertion from a provider adapter.
2. Ask the binding registry for its binding key.
3. If a subject exists, do not create another one.
4. If no subject exists, run the application creation policy.
5. Create an opaque local subject ID only when the policy accepts.
6. Start a durable link workflow with one request ID.
7. Claim the binding key and initialize the subject idempotently.
8. Create the application profile through an application-owned command. Use
   the authentication completion ID as its idempotency key.
9. Start a session only after the subject is active.

The application must recover a crash between identity creation and profile
creation according to its policy. Identity does not make profile state part of
the subject actor.

## Link a provider to an existing subject

1. Require a current authenticated local session.
2. Start the new provider flow as a link operation, not a login operation.
3. Bind the transaction to the current subject.
4. Show the verified provider identity before confirmation when the interface
   permits it.
5. Require an unsafe request protected by the application's CSRF policy for
   final confirmation.
6. Start a durable link workflow.
7. Claim the provider binding and add its reference to the subject.
8. Return the same result for an already completed request ID.

Do not link by matching email. Do not let the provider callback select a
different subject from the one bound to the link transaction.

## Unlink an authentication method

1. Require a recent authenticated session according to application policy.
2. Identify the exact binding key.
3. Check that the subject retains an allowed authentication method.
4. Start a durable unlink workflow.
5. Mark the subject binding unusable before releasing or tombstoning the
   registry key.
6. Increment session generation when policy requires reauthentication.
7. Record the unlink result without provider tokens or secrets.

The safe default keeps a binding tombstone and forbids automatic reassignment.
The application decides whether an identifier or provider account may ever be
reused.

## Add local password authentication

1. Configure identifier normalization.
2. Configure PBKDF2 algorithm, salt, derived-key length, work factor, input
   limit, and accepted hash versions.
3. Benchmark the work factor on each deployment class.
4. Normalize the submitted identifier before binding lookup.
5. Ask the throttle actor for admission.
6. Query the subject for stored verification material after lookup succeeds.
7. Run password derivation at the request or identity boundary.
8. Use a dummy hash and the same bounded work when lookup fails.
9. Record the generic success or failure with the throttle actor.
10. Send only a replacement hash when rehash is required.

Never send a raw password in an actor command, query, effect, session, or log.

## Record provider claims in an application profile

1. Install an application success handler for authentication.
2. Receive the completion ID, subject, provider, authority, authentication
   time, and bounded claims.
3. Send `ObserveIdentityClaims` to `profile/{subject}`.
4. Use the completion ID as the command idempotency key.
5. Let the profile actor choose which provider supplies each display field.
6. Record claim provenance and observation time when the application needs
   them.
7. Accept the login only after the required profile command succeeds.
8. Load the application profile by session subject on later requests.

Treat claims as provider observations. A changed or missing claim does not
change a binding. Do not put profile claims in `web.sessions`. Escape display
text. Treat a picture URL as untrusted presentation input. Allowlist it or
fetch it through the application's bounded proxy policy.

## Issue a local recovery token

1. Resolve the password binding without exposing whether it exists.
2. Send an issue command to the subject actor when it exists.
3. Generate a 16-byte public request ID and 32-byte secret outside the update.
4. Store the raw secret in a short-lived delivery outbox.
5. Return only `SHA-256(secret)` to the subject actor.
6. Deliver a selector containing subject and request ID plus the separate
   URL-safe secret.
7. Hash the submitted secret at the request boundary.
8. Let one subject update consume the digest, replace the password hash, and
   increment session generation.

Redact URL secrets from access logs. Make token preparation and delivery
idempotent by request ID.

## Bridge any provider to a local session

1. Accept only a verified assertion or successful password result.
2. Resolve its binding to one local subject.
3. Ask the subject actor for active status and session generation.
4. Create one `AuthenticationResult` with a stable completion ID.
5. Call the application success handler with that result.
6. Write only subject and generation with `web.sessions.Sessions` after the
   handler accepts.
7. On each protected request, compare the cookie generation with the subject.
8. Reject and clear a stale or inactive session.

The session does not record the provider that authenticated it unless an
application has an explicit step-up or audit requirement.

## Revoke local sessions after a provider event

1. Validate the event through the provider adapter.
2. Resolve the provider binding to a subject.
3. Decide whether the event requires local revocation.
4. Increment the subject session generation when it does.
5. Disable or unlink the binding only through its durable workflow.

Without such an event or an online provider check, a valid local session does
not learn that the external provider disabled an account.
