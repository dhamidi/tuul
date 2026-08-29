---
name: agentdoc
description: >-
  Write or rewrite doc comments so an agent can act on them without a human
  to resolve ambiguity. Use when adding or editing comments on a type or
  member, when `tuul docs` will surface the text, or when the user asks for
  agentdoc, agent-facing docs, or a comment an agent will parse.
  Not for README marketing, commit messages, or inline notes that only a
  human reading the source will see.
---

# agentdoc

Doc comments are a deliverable. A tool prints them. An agent reads them
mid-task and decides what to call. Write as if that agent is the next
reader, not a human who will “get the idea.”

This skill is the house style for those comments. It follows the same
structural rules as Simplified Technical English (one claim per sentence,
active voice, no synonym rotation). It does not try to match ASD's word list.

Linking syntax depends on the comment ecosystem. The default is
[JavaDoc with Markdown](references/javadoc-markdown.md). Load that file, or
another ecosystem file under `references/`, before you write a link.

## When to use

- You add or rewrite a doc comment on a type or member.
- The user asks for a better type comment, agent-facing docs, or agentdoc.
- A comment reads as a slogan, a metaphor, or two policies in one sentence.

Do not use this skill for README voice, commit messages, or inline notes
that only a human reading the source will see.

## Shape

- One claim per sentence. Use a period. Do not use a semicolon.
- One topic per paragraph. Keep the paragraph short.
- When order matters, say the steps in order. Then name the member that is
  that loop.
- Sentence length: aim at 25 words or fewer for description, 20 or fewer for
  a procedure.

## Voice

- Active voice. Simple present.
- Name the actor: the update, the handler, the dispatching thread, the caller.
- Prefer the verb. Do not hide work in a noun (“composition,” “registration”)
  when a verb will do.

## Words

- Pick one verb per action and keep it: apply, run, emit, request, register,
  return. Do not rotate synonyms for the same action in one comment.
- Prefer a single verb over a phrasal verb (run, not carry out; stop, not
  give up).
- Keep domain nouns (`error`, effect, state, message). Define them by what
  they do in the next sentence, not by analogy.

## Claims

- State what happens. Then state the exception. Then state why the exception
  exists.
- Do not upgrade a hedge. If the code *may* drop a message, write “may.”
- If a failure path exists, describe it as behavior, not as a virtue. Do not
  open with a slogan (“fail open”) unless the next sentence states the
  mechanics.

## Contract

The comment is done only when an agent can call the member from the comment
alone. Walk the body. Give each of these a sentence if the code has it:

- Empty input and the default when a parameter is absent.
- Each distinct type the code emits. Do not fold two types into one name.
- What the return value contains, and the moment it stops changing.
- Order: argument order, or arrival order if work runs at the same time.
- What this member does not do, when a paired member does it.
- What the caller must still do, when this member cannot.

One name per member. Do not invent a second name for the same member.

## Links

When a sentence is about another member, link to it. Use the linking
syntax of this comment ecosystem. Do not narrate the implementation. Do
not restate the signature. Do not link to a private member. Describe the
public result instead.

## Examples

- Show the smallest program that constructs, registers, and dispatches (or
  the equivalent for that type).
- Let the code carry the API. The prose around it states invariants the
  example cannot show.

## What not to do

- Do not open with a metaphor and then explain it.
- Do not sell (“mini-framework,” “robust”) without the mechanical sentence
  that follows. Prefer deleting the adjective.
- Do not put two policies in one sentence. Updates compose. Effects replace.
  That is two sentences.

## Process

1. Read the type, the public members, and the body of the member you document.
2. Load the linking reference for this comment ecosystem. Default:
   [JavaDoc with Markdown](references/javadoc-markdown.md).
3. Write or rewrite to the rules above. One topic per paragraph.
4. Walk the body again. If a branch, default, emitted type, or frozen return
   has no sentence, the comment is not done.
5. Check against [examples/bad.md](examples/bad.md) and
   [examples/good.md](examples/good.md).
6. Default output is the comment text, ready to paste. Do not add a preamble
   about this skill.

If the user asked only to see a comment, print it. Do not write the file
unless they asked to write it.
