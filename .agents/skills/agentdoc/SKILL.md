---
name: agentdoc
description: >-
  Write or rewrite doc comments so an agent can act on them without a human
  to resolve ambiguity. Use when adding or editing comments on a type,
  member, constant, or options value, when a docs tool such as `tuul docs`
  will print the text, or when the user asks for agentdoc, agent-facing
  docs, or a comment an agent will parse. Also covers Markdown that the same
  docs tool prints beside the code. Not for marketing copy, commit messages,
  or inline notes that only a human reading the source will see.
---

# agentdoc

A doc comment is a deliverable. A tool prints it. An agent reads it
mid-task and decides what to call, with what, and what it gets back. Write
for that reader. It does not "get the idea." It acts on the sentence.

This skill is a house style, not a language. It applies to a Python
docstring, a Go doc comment, a Rust `///`, a TSDoc block, and a JavaDoc
`///` alike. Linking syntax is the one thing that differs by ecosystem.
Load [JavaDoc with Markdown](references/javadoc-markdown.md) or
[other ecosystems](references/other-ecosystems.md) before you write a link.

## When to use

- You add or rewrite a doc comment on a type, member, constant, enum case,
  or options record.
- You write or edit Markdown that the docs tool prints beside the code.
- The user asks for a better comment, agent-facing docs, or agentdoc.
- A comment reads as a slogan, a metaphor, a history, or two policies in one
  sentence.

Do not use it for marketing copy, commit messages, or inline notes that
only a human reading the source will see.

## The reader's questions

Answer these, in this order, for every public member:

1. When do I call this, and instead of what?
2. What do I pass, and what does an absent or empty argument mean?
3. What do I get back, field by field when it is structured, and when does
   it stop changing?
4. What happens on each path that is not the main one: empty input, missing
   name, failure, timeout. Name the type or value each path produces.
5. What must I still do, when this member does not do it for me?

A value gets the same treatment: what it means, who passes it, and what the
default is.

## First sentence

The first sentence is the thing or the value, then when to use it. Not
the signature. Not the method's name in other words.

- Value: "The number of matches one search answers with. Pass it as the
  limit to `search`."
- Function: "The documents of one package. Only the document index is
  refreshed for this, so reading Markdown does not compile code."
- Type: "How much of a symbol to include. Pass `BARE` for the symbol alone."

Do not open with "This function returns", "Returns the", "Answers", or the
identifier. The reader can see the identifier.

## Shape

- One claim per sentence. Use a period. Do not use a semicolon or a dash to
  join two claims.
- One topic per paragraph. Keep the paragraph short.
- When order matters, say the steps in order. Then name the member that is
  that loop.
- Aim at 25 words or fewer per sentence, 20 or fewer in a procedure.

## Voice

- Active voice. Simple present.
- Name the actor: the update, the handler, the dispatching thread, the
  caller.
- Prefer the verb. Do not hide work in a noun ("registration",
  "composition") when a verb will do.

## Words

- One verb per action, kept across the whole file and across every file
  you touch in one change: run, emit, request, register, return, record.
  Before you edit more than one file, write the verb for each action down
  and use only those.
- One name per thing. Do not call the same value "the wait bound" in one
  comment and `patience` in the next.
- Prefer a single verb over a phrasal verb: run, not carry out. Stop, not
  give up.
- Keep domain nouns. Define one by what it does in the next sentence, not
  by analogy.
- Do not coin a phrase and repeat it. "A name you can ask for next" is a
  definition the first time and filler every time after. Say the concrete
  action instead: "pass it to `lookup`."

## Claims

- State what happens. Then state the exception. Then state why the
  exception exists, only when the code shows the reason. A reason the code
  does not show is a guess. Delete it.
- Do not upgrade a hedge. If the code *may* drop a message, write "may."
- Describe a failure path as behavior, not as a virtue. Do not open with a
  slogan ("fail open") unless the next sentence states the mechanics.
- Do not narrate the implementation. Do not narrate history: "used to",
  "no longer", "was once". The reader calls the code as it is.
- Do not append a justification to a rule that needs none. "The whole
  comment, because that is what was asked for" is a rule plus an opinion.
  Keep the rule.

## Contract

The comment is done only when the reader can call the member from the
comment alone. Walk the body. Give each of these a sentence if the code
has it:

- Empty input and the default when a parameter is absent.
- Each distinct type or value the code emits. Do not fold two into one
  name.
- What the return value contains, and the moment it stops changing.
- Order: argument order, or arrival order if work runs at the same time.
- What this member does not do, when a paired member does it.
- What the caller must still do, when this member cannot.

## Links

When a sentence is about another member, link to it in the syntax of the
ecosystem. Do not link to a private member. Describe the public result
instead. Do not restate the signature. Do not invent a second name for a
member you could link.

## Examples in comments

- Show the smallest program that constructs, registers, and calls, or the
  equivalent for that type.
- Let the code carry the API. The prose around it states invariants the
  example cannot show.

## Markdown the docs tool prints

A README, reference, tutorial, or how-to that the docs tool serves is read
by the same agent. The rules above apply. In addition:

- A lead-in before a code block says when to run the command, in the
  imperative: "Use search when you do not know the full name:". A lead-in
  that is clever and says nothing is deleted.
- A paragraph opens with what the reader does, not with a fact about the
  system.
- Facts, commands, and examples are checked against the code the same way
  a comment is.

## Process

1. Read the whole type or module, not the diff. Read every public member
   and the body of the member you document. A rewrite scoped to changed
   lines rewrites sentences without the code in view, and that is how a
   false claim gets a second, better-worded life.
2. Load the linking reference for this ecosystem:
   [JavaDoc with Markdown](references/javadoc-markdown.md) or
   [other ecosystems](references/other-ecosystems.md).
3. Write the verb list for the actions in this file. Reuse the verbs the
   surrounding comments already use.
4. Write or rewrite to the rules above. First sentence first.
5. Walk the body again for coverage. If a branch, default, emitted type, or
   frozen return has no sentence, the comment is not done.
6. Walk the comment for truth. For each sentence, point at the line of
   code that makes it true. No line, no sentence. This step is where an
   inherited wrong claim dies.
7. Read the comment as the reader. Can you call the member from it alone,
   and do you know what you get back? If not, go to step 4.
8. Check against [examples/bad.md](examples/bad.md) and
   [examples/good.md](examples/good.md).
9. Default output is the comment text, ready to paste. Do not add a
   preamble about this skill.

If the user asked only to see a comment, print it. Do not write the file
unless they asked to write it.

## Splitting the work

If you hand parts of one change to other agents, give each of them the
whole type to read, the verb list from step 3, and the rule that a sentence
without a line of code behind it is deleted. Then read every comment they
wrote before you commit it. Compiling and passing tests do not check a
sentence.
