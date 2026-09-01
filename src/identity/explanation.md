# Explanation

This document explains the proposed provider-neutral `identity` design. It is
not an implementation. Read [reference.md](reference.md) for the exact proposed
rules.

## Authentication ends before the session begins

An authentication provider answers one question: which provider identity did
this request prove? The binding registry answers a second question: which local
subject owns that provider identity? The subject actor answers a third: may the
subject start a local session now?

`web.sessions` begins after those answers. It carries the local subject and
session generation. It does not need to know whether a password, Google,
another OpenID Connect issuer, or a custom protocol supplied the proof.

This boundary keeps a provider migration out of application authorization and
session handling.

## A password is a provider

The first design made password concepts part of identity core. That made local
identifier normalization, dummy-hash work, recovery, and throttling look
universal. Redirect providers do not use that flow.

The revised design treats password authentication as one provider. It returns
the same assertion shape as an external provider. Its binding key uses the
identifier namespace and normalized identifier. Its provider-specific code
owns hashing, recovery, and credential throttling.

Identity core now owns only the operations that every provider needs: binding,
subject status, linking policy, and local session creation.

## An external account is not a local subject

One person can use a password and several provider accounts. One provider
account must not authenticate two local subjects. A binding records that
ownership without turning the provider account into the application profile.

The binding key includes provider name, authority, and external subject. The
authority prevents equal subject strings from different issuers from
colliding. The provider name keeps two configurations separate when an
application needs different policies for the same issuer.

The local subject remains opaque. The application can attach any profile or
resource ownership to it without putting that state in identity actors.

## Email is a claim, not an account key

An OpenID Connect provider proves an issuer and subject. An email claim can
change. Two providers can assert the same address. An address can also match a
local password identifier without proving that the same person controls the
existing local subject.

Automatic email linking turns a provider claim into an account-takeover rule.
The default therefore refuses an unknown provider binding. An application may
use a verified email during explicit creation or linking, but the binding key
still uses issuer and subject.

## Linking is a durable workflow

The binding registry and subject actor own different decisions. One claims a
globally unique binding. The other records which methods the subject accepts.
Their journals cannot commit atomically.

A durable link actor records the intended operation and every accepted step.
It uses one request ID in every command. Replay lets it retry an incomplete
step. Idempotent handlers make a repeated step return the prior result.

This does not create a distributed transaction. It makes incomplete work
visible and recoverable with actor semantics already present in Tuul.

## Redirect login state is transient

State, nonce, and a PKCE verifier bind a browser redirect to one login attempt.
They are secrets or one-use proofs. They do not belong in a permanent subject
log.

The default ephemeral login actor keeps them only until callback or expiry. A
restart invalidates the attempt. Retrying a login is cheaper and safer than
retaining every verifier in durable history.

A deployment with several callback processes may use an encrypted external
store owned by an ephemeral actor. A readable signed cookie can hold an opaque
transaction selector. It must not hold the verifier, nonce, or client secret.

## A verified assertion is the provider boundary

Identity core should not understand JWTs, authorization codes, SAML documents,
passkey responses, or provider-specific user APIs. Each adapter validates its
protocol and returns one bounded assertion.

The assertion contains only the stable binding key, optional authentication
time, and selected claims. It contains no reusable provider credential. Core
code can then apply one binding and subject protocol to every provider.

This boundary is also the extension point. A new provider implements proof
validation once. It does not add another subject type or session format.

## Claims cross into the application once

Provider display names, email addresses, usernames, and picture URLs can
change. They are observations from one authentication. They are not durable
identity keys.

Identity therefore returns them in one authentication result after it resolves
the local subject. The result also contains a completion ID. An application
success handler uses that ID when it sends an observation to the profile actor.

The profile actor owns durable presentation choices. It decides which provider
supplies a display name. It decides whether a verified email replaces an older
email. It decides whether to render, proxy, or cache a picture URL.

The identity package does not keep a second profile. The session cookie does
not keep a claim snapshot. Later requests use the session subject to query the
application profile actor.

This handoff can run more than once after a crash. The completion ID makes the
profile command idempotent. Identity writes the session only after the
application handler accepts the result.

## OpenID Connect is one implementation, not one provider

Google, Microsoft, and many other services implement OpenID Connect. The
redirect and token protocol is reusable. Issuer, client configuration, scopes,
algorithms, and selected claims vary.

A generic OpenID Connect implementation therefore belongs in the package.
Google should be a named configuration of it. Another conforming issuer should
require configuration rather than Java code.

A service that does not provide a conforming identity assertion needs its own
adapter. OAuth authorization alone does not identify a person. The adapter must
obtain and validate a stable provider subject before it returns an assertion.

## Provider tokens are a different authority

An ID token proves authentication to the client. An access token authorizes a
call to a provider API. A refresh token can create more access tokens. Their
lifecycles and risks differ from the local subject session.

Login-only identity discards provider API tokens. An application that needs
them installs a separate token-owning service. That service can use actors to
serialize refresh and revocation without putting tokens in subject history.

Keeping token ownership separate prevents a simple “Log in with Google” button
from silently creating a durable Google API credential store.

## Local logout is local

Clearing the Tuul session ends the application session in that browser. It does
not end the provider session. It also does not revoke an access token or learn
that a provider account was disabled later.

Session generation gives the local subject an immediate local revocation
decision. Provider event handling or online revalidation is an optional input
to that decision. The application must choose the latency and availability
cost of such checks.

## Authorization stays with the application

A verified Google employee, a verified password user, and a service identity
can all resolve to local subjects. Authentication does not say which invoice,
document, or actor they may access.

The actor that owns an application resource owns its authorization decision.
Identity supplies the authenticated subject and relevant authentication facts
when an application explicitly requests them. It does not define roles or
permissions beside the resource-owning actors.
