# agents

`agents` is a proposed package for running LLM agents on top of `actors`. It
is a design document. It is not implemented, and no type, address, or message
name in these documents is a public API.

The package answers one question: which actors run an agent, and what each
one keeps, for how long, and where. The answer must let every run be browsed
and shared inside a workspace that several people use, and it must keep every
run until a person deletes it or moves it to cold storage.

Read [tutorial.md](tutorial.md) to follow one run from start to archive. Read
[howto.md](howto.md) for one task at a time. Read [reference.md](reference.md)
for the exact rules. Read [explanation.md](explanation.md) for the reasons.

## The hierarchy

Every address is `type/id`. The id carries the owner as its prefix, so
`Logs.catalogue(type, prefix)` lists the children of one owner without an
index. The tree below is an ownership tree of names and messages. It is not a
supervision tree. The `actors` package has none, because summoning is restart.

```
workspace/{ws}                       durable    members, roles, settings
├── member/{ws}/{subject}            durable    one person's choices in this workspace
├── agent/{ws}/{agent}               durable    one agent definition and its versions
├── budget/{ws}/{yyyy-mm}            durable    the spend ledger of one month
├── runs/{ws}/{yyyy}                 durable    which runs exist, their visibility, hot or cold
│   └── search/{ws}                  ephemeral  full-text index over runs, derived
└── run/{ws}/{run}                   durable    the transcript and status of one run
    ├── run/{ws}/{run}/{child}       durable    a subagent run, same type, nested id
    ├── stream/{ws}/{run}            ephemeral  the tokens of the turn in flight
    ├── tool/{ws}/{run}/{call}       ephemeral  one tool invocation
    ├── presence/{ws}/{run}          ephemeral  who is watching right now
    └── archive/{ws}/{run}           durable    one move to or from cold storage
```

Three things that hold state are not actors:

- the **blob store** holds large tool outputs and files by content hash;
- the **search database** is one derived SQLite file per workspace;
- the **cable** carries live updates to open browser tabs.

## Durable or ephemeral, and why

An actor is durable when its log is the only record of what happened. An
actor is ephemeral when its state has another home, or when a restart may
discard it. No actor is both. That rule comes from `actors` and this design
follows it without exception.

| Actor | Kind | Why |
|---|---|---|
| `workspace` | durable | Who is a member is a decision. No store holds it twice. |
| `member` | durable | Per-person choices must survive a restart. Read cursors do not, and they stay out of this log. |
| `agent` | durable | An agent version is the recipe that a run must be able to cite forever. |
| `budget` | durable, `full` | A spend ledger cannot be reconstructed by anybody else. |
| `runs` | durable | It decides that a run exists, is visible, is archived, or is deleted. |
| `run` | durable, `full` | The transcript is the product. The model's answers and tool results are facts nobody can recompute. |
| `archive` | durable | A move to cold storage must resume after a crash between its steps. |
| `search` | ephemeral | Its state is a derived SQLite file. A rebuild replays the catalogue and the runs. |
| `stream` | ephemeral | Partial tokens are discarded when the turn ends. The complete answer is one run command. |
| `tool` | ephemeral | It owns a process. A restart kills the process, and the run re-issues the call. |
| `presence` | ephemeral | Who is watching is true only while the connection is open. |

## Lifetime and size

Two lifetimes matter. Residency is how long the actor stays in memory before
`Spawn.idle` evicts it. Retention is how long its record exists. A durable
actor comes back from its log after eviction. An ephemeral actor comes back
empty.

| Actor | Residency (`idle`) | Retention | State in memory | Log on disk | Growth |
|---|---|---|---|---|---|
| `workspace/{ws}` | 1 h | forever, until deleted | KBs. Members and settings. | KBs to low MBs | One command per membership or settings change. |
| `member/{ws}/{subject}` | 10 min | forever, until the member or workspace is deleted | under 1 KB | KBs | One command per changed preference. |
| `agent/{ws}/{agent}` | 30 min | forever, until deleted | tens of KBs. Every version's prompt. | tens of KBs to MBs | One command per version. |
| `budget/{ws}/{yyyy-mm}` | 10 min | forever | under 1 KB. Totals per agent and person. | about 150 B per turn. 100k turns is 15 MB. | Bounded by the month. A new month is a new actor. |
| `runs/{ws}/{yyyy}` | 1 h | forever | about 300 B per run. 50k runs is 15 MB. | about 1.5 KB per run | About five commands per run. Bounded by the year. |
| `run/{ws}/{run}` | 15 min while active. Settled when finished. | forever, hot or cold, until deleted | The context window. 1 to 4 MB for a long conversation. | 0.5 to 2 MB for 50 turns. 10 to 40 MB for 1,000 turns. | One command per model answer, tool result, person's message, or status change. Inline bodies stop at 64 KiB. |
| `run/{ws}/{run}/{child}` | as `run` | as `run` | as `run` | as `run` | A child holds its own transcript. The parent holds only the child's summary. |
| `archive/{ws}/{run}` | 5 min. Settled when done. | forever | under 1 KB | under 4 KB | About ten commands per move. |
| `search/{ws}` | 1 h | none. The SQLite file is derived and can be deleted. | none. It writes through an effect. | none | The database is about 1.5 times the text it indexes. |
| `stream/{ws}/{run}` | 1 min | one turn | one turn's output. Up to a few hundred KBs. | none | Reset at every turn. |
| `tool/{ws}/{run}/{call}` | never idle. Ends with the call. | one call | a process handle and 64 KiB of buffered output | none | Output above 64 KiB streams to a blob. |
| `presence/{ws}/{run}` | 1 min | while a tab is open | bytes per viewer | none | One entry per open connection. |

The blob store keeps one file per content hash under the run's prefix. A
blob lives as long as its run and moves with it. The search database is one
file per workspace and is never moved to cold storage. A rebuild recreates it.

## What forever means

The system deletes nothing on its own. A run is kept hot until a person runs
one of two explicit operations:

- **archive** moves the run's journal and blobs to cold storage. The
  catalogue keeps the run's title, agent, owner, dates, and final summary. The
  run stays listed and its summary page stays browsable. **restore** moves
  it back.
- **delete** erases the journal and blobs, hot or cold, and records a
  tombstone in the catalogue. The id is never reused.

Nothing compacts a log and nothing snapshots one. That is a known limit of
`actors`, and this design bounds every log by construction instead: budgets
by month, catalogues by year, runs by the size of a conversation, and
subagent work by giving each child its own run.
