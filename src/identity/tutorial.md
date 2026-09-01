# Tutorial: password and Google login for one subject

This tutorial walks through the proposed `identity` contract. The package is
not implemented. The names describe a future API and actor protocol.

The example application accepts a local password or Google OpenID Connect. Both
methods resolve to the same opaque local subject. The application owns the
person profile and all authorization decisions.

## 1. Start with the local subject

Use one durable actor system. Install these actor responsibilities:

| Actor | Owns |
|---|---|
| `identity/subject/{subject}` | Subject status, session generation, binding references, password credential, and local recovery state. |
| `identity/bindings/{shard}` | One unique authentication binding key to one subject. |
| `identity/link/{request}` | One recoverable account-creation, link, or unlink workflow. |
| `identity/throttle/{key}` | Bounded local-password admission and failure state. |

The binding actor is the authority for uniqueness. The subject actor is the
authority for whether a subject is active and which bindings it recognizes.
The link actor coordinates the two authorities with an idempotent request ID.

Redirect authentication also uses `identity/login/{request}`. This actor is
ephemeral by default. It owns one short-lived state, nonce, PKCE verifier,
return location, provider name, and expiry. A process restart invalidates the
attempt and the person starts again. A deployment that needs login attempts to
survive a restart can put this state in one actor-owned encrypted store.

## 2. Configure the password provider

The password provider uses a local binding key:

```text
(provider=password, authority=email, external-id=<normalized email>)
```

Configure identifier normalization and a password policy before the actor
system starts. The default identifier policy strips surrounding whitespace,
applies Unicode NFKC, and applies `toLowerCase(Locale.ROOT)`. Use the same
policy for sign-up, login, recovery, and throttling.

The proposed password algorithm is `PBKDF2WithHmacSHA256` through JCA. Configure
the salt length, derived-key length, work factor, maximum input size, and
accepted stored versions. Benchmark the work factor on each deployment class.
Fail startup when the configured provider does not supply the algorithm.

The request boundary hashes and verifies passwords. It never sends a raw
password to an actor. The subject actor returns stored verification material
through a query. A durable rehash command contains only the replacement hash.

## 3. Configure Google as OpenID Connect

Create an OpenID Connect provider configuration named `google`. Configure:

- the exact accepted issuers `https://accounts.google.com` and
  `accounts.google.com`;
- the canonical binding authority `https://accounts.google.com`;
- the discovery document location;
- the client ID and client-secret reference;
- the exact callback URL;
- the scopes `openid`, `profile`, and `email`;
- the allowed ID-token signature algorithms;
- bounded discovery and key-cache lifetimes.

Keep the client secret in the runtime secret boundary. Do not put it in actor
state, a browser cookie, or source control.

OpenID Connect uses this binding key after validation:

```text
(provider=google, authority=https://accounts.google.com, external-id=<validated sub>)
```

Do not substitute the email claim for `sub`. The email is an optional claim.
It may help the application create a profile or propose an explicit link.

Map Google's selected claims to the common identity claim names:

| Google claim | Identity claim |
|---|---|
| `name` | `displayName` |
| `email` | `email` |
| `email_verified` | `emailVerified` |
| `picture` | `pictureUrl` |

## 4. Compose the web boundary

Install the identity feature with the application's router. The future feature
contributes provider start and callback handlers, transaction middleware, and
the session bridge. The application chooses the mount point, login page,
success handler, failure handler, and post-login destination.

The success handler receives one authentication result. It sends an
idempotent observation command to the application profile actor before it
accepts the login. Identity writes the session only after the handler accepts.

Run signed-session middleware before the identity session bridge. Run ordinary
form CSRF middleware around password sign-up, password login, recovery, link,
and unlink forms.

An OpenID Connect callback does not use the form CSRF token. Its login
transaction validates state, nonce, and PKCE instead. The callback route must
accept only the configured provider and exact callback URL.

## 5. Create a password subject

The sign-up flow is:

1. Capture and validate the application form.
2. Normalize the local identifier.
3. Hash the password at the request boundary.
4. Create an opaque subject ID.
5. Ask a link actor to claim the password binding and initialize the subject.
6. Store the versioned password hash in the subject actor.
7. Issue an optional local verification token from the subject actor.
8. Return the application's generic sign-up response.

The link workflow records every accepted step. It retries idempotently after a
crash between the binding claim and subject update. It does not assume that two
actor journals commit atomically.

## 6. Sign in with a password

The password flow is:

1. Normalize the submitted identifier.
2. Ask the throttle actor for admission.
3. Resolve the password binding to a subject.
4. Query the subject for password verification material and status.
5. Run bounded PBKDF2 verification at the request boundary.
6. Run the same work against a dummy hash when no binding exists.
7. Record success or failure with the throttle actor.
8. Rehash with a durable command when policy requires an upgrade.
9. Create an authentication result with the subject, generation, password
   provider, authentication time, and selected local claims.
10. Let the application success handler observe the result.
11. Write the opaque subject and session generation with `web.sessions`.

The browser receives one public failure for an unknown identifier, wrong
password, inactive subject, unverified subject, or throttle refusal.

## 7. Sign in with Google

The Google flow is:

1. The start handler creates a one-use login transaction.
2. An external boundary generates state, nonce, and a PKCE verifier. It derives
   the PKCE challenge with S256.
3. The handler redirects to Google's authorization endpoint.
4. Google redirects to the exact configured callback with an authorization
   code and state.
5. The callback consumes the matching login transaction once.
6. The OpenID Connect provider exchanges the code with the saved verifier.
7. The provider validates the ID-token signature, algorithm, issuer, audience,
   expiry, authorized party when present, and nonce.
8. The provider returns a verified assertion containing `google`, canonical
   authority, `sub`, authentication time when present, and selected claims.
9. The binding registry resolves `(google, authority, sub)` to a local subject.
10. The subject actor returns active status and session generation.
11. Identity creates an authentication result with normalized selected claims.
12. The application success handler records the claims in its profile actor.
13. The identity session bridge writes the same local session used by password
    login after the handler accepts.

The package does not store the Google access token or refresh token during a
login-only flow. An application that calls Google APIs must install a separate
token-owning service with its own encryption, refresh, revocation, and scope
policy.

## 8. Record a friendly application profile

Every successful provider produces the same `AuthenticationResult` shape:

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

The application success handler sends the result to its profile actor:

```text
profile/{subject} <- ObserveIdentityClaims(
    completionId, provider, authenticatedAt, claims)
```

The profile actor uses `completionId` to ignore a repeated observation. It
decides which provider supplies each display field. It also decides whether to
replace, proxy, or cache a remote picture URL.

The result is a snapshot from this authentication. Identity does not retain
it. Later requests use the session subject to query the application profile.

## 9. Decide what an unknown Google account means

A verified assertion is not permission to create or link an account. When the
binding registry has no Google binding, call the application's configured
decision:

- refuse the login;
- create a new subject;
- require an invitation;
- require an already authenticated subject to confirm a link.

The safe default is refusal. Never link to an existing subject only because an
email claim matches a local identifier. For an explicit link, require the
current local session, show the provider identity to the person, and use a
durable link actor to claim the external binding.

## 10. Use one session after authentication

Both providers finish with the same session payload:

```json
{
  "subject": "opaque-application-subject",
  "sessionGeneration": 4
}
```

The identity bridge checks the signed session and asks the subject actor for
current status and generation. Incrementing the generation revokes every local
session for that subject.

Local logout clears the application cookie. It does not sign the person out of
Google, revoke a provider token, or learn that Google later disabled the
account. Those operations require an explicit provider-session or token policy.

## Continue

Read [howto.md](howto.md) for individual tasks. Read
[reference.md](reference.md) for the proposed contracts. Read
[explanation.md](explanation.md) for the design reasons.
