# agents

`agents` is a proposed package for running LLM agents on top of `actors`. It
is a design document. It is not implemented, and no type, address, or message
name in these documents is a public API.

The target is a team product that works like Claude: a team of 20 to 30
people, one deployment per workspace, every run browsable and shared, every
run streamed live to every surface that is watching it, and a feed that
shows what teammates are doing right now. The system keeps every run until a
person deletes it or moves it to cold storage.

Read [tutorial.md](tutorial.md) to follow one run from start to archive. Read
[howto.md](howto.md) for one task at a time. Read [reference.md](reference.md)
for the exact rules. Read [explanation.md](explanation.md) for the reasons,
and [turn.md](turn.md) for one turn with many tools in flight.

## One deployment is one workspace

A deployment runs one `ActorSystem`, one process, one log root, one blob
directory, one search database, and one identity. There is no workspace id in
any address. The workspace actor is the singleton `workspace`, addressed with
an empty id. A second team is a second deployment.

## The hierarchy

Every address is `type/id`. The id carries the owner as its prefix, so
`Logs.catalogue(type, prefix)` lists the children of one owner without an
index. The tree below is an ownership tree of names and messages. It is not a
supervision tree. The `actors` package has none, because summoning is restart.

```
workspace                         durable    members, roles, settings, archive location
├── member/{subject}              durable    one person's choices
├── project/{project}             durable    instructions, files, tools, model, versions
├── budget/{yyyy-mm}              durable    the spend ledger of one month
├── runs/{yyyy-mm}                durable    which runs exist, visibility, hot or cold
├── search                        ephemeral  full-text index over runs, derived
├── activity                      ephemeral  what every member is doing right now
└── run/{run}                     durable    the transcript and status of one run
    ├── run/{run}/{child}         durable    a subagent run, same type, nested id
    ├── live/{run}                ephemeral  deltas, presence, and typing for one run
    ├── tool/{run}/{call}         ephemeral  one tool invocation
    └── archive/{run}             durable    one move to or from cold storage
```

Three things that hold state are not actors:

- the **blob store** holds large tool outputs and files by content hash;
- the **search database** is one derived SQLite file;
- the **cable** carries one event stream per connected surface.

## Surfaces

A surface is anything that shows a run: the web page, a terminal client, an
editor extension, a phone. Every surface speaks HTTP and reads one
`text/event-stream` connection through `web.cable`. The stream carries JSON
events for every surface and Turbo Stream HTML for the browser, on the same
topics and with the same sequence numbers. A surface that reconnects says
where it got to and receives what it missed, or learns that it must reload.

A surface can also execute tools. A run started from a terminal runs its
shell and file tools on that machine. Every other surface still watches the
output live, because the output passes through the run's `live` actor either
way.

## Durable or ephemeral, and why

An actor is durable when its log is the only record of what happened. An
actor is ephemeral when its state has another home, or when a restart may
discard it. No actor is both. That rule comes from `actors` and this design
follows it without exception.

| Actor | Kind | Why |
|---|---|---|
| `workspace` | durable | Who is a member is a decision. No store holds it twice. |
| `member` | durable | Per-person choices must survive a restart. Read cursors do not, and they stay out of this log. |
| `project` | durable | A project version is the recipe that a run must be able to cite forever. |
| `budget` | durable, `full` | A spend ledger cannot be reconstructed by anybody else. |
| `runs` | durable | It decides that a run exists, is visible, is archived, or is deleted. |
| `run` | durable, `full` | The transcript is the product. The model's answers and tool results are facts nobody can recompute. |
| `archive` | durable | A move to cold storage must resume after a crash between its steps. |
| `search` | ephemeral | Its state is a derived SQLite file. A rebuild replays the catalogue and the runs. |
| `activity` | ephemeral | Who is doing what now is true only now. Recording it would be surveillance, not history. |
| `live` | ephemeral | Partial tokens, viewers, and typing are discarded when the turn ends or the tab closes. |
| `tool` | ephemeral | It owns a process or a surface connection. A restart kills both, and the run re-issues the call. Its result is a run command, so the run is the record. |

## Lifetime and size

Two lifetimes matter. Residency is how long the actor stays in memory before
`Spawn.idle` evicts it. Retention is how long its record exists. A durable
actor comes back from its log after eviction. An ephemeral actor comes back
empty.

The numbers assume 25 members, 20 runs per member per day, 250 working days,
and 30 messages per run. That is 125,000 runs and about 4 million
transcript entries per year.

| Actor | Residency (`idle`) | Retention | State in memory | Log on disk | Growth |
|---|---|---|---|---|---|
| `workspace` | never evicted | forever | KBs. Members and settings. | KBs to low MBs | One command per membership or settings change. |
| `member/{subject}` | 10 min | forever, until the member is removed | under 1 KB | KBs | One command per changed preference. |
| `project/{project}` | 30 min | forever, until deleted | tens of KBs. Every version's instructions. Files are blob references. | tens of KBs to MBs | One command per version or file change. |
| `budget/{yyyy-mm}` | never evicted | forever | under 1 KB. Totals per project and person. | about 150 B per turn. 25 MB for a busy month. | Bounded by the month. |
| `runs/{yyyy-mm}` | 1 h | forever | about 300 B per run. 3 MB for 10,000 runs. | about 1 KB per run. 10 MB per month. | Three commands per run, plus one per retitle or share. |
| `run/{run}` | 15 min after the last message | forever, hot or cold, until deleted | The context window. 1 to 4 MB for a long conversation. | 0.3 to 1 MB for 30 messages. 10 to 20 MB for 500. | One command per person's message, model answer, tool result, or status change. Inline bodies stop at 64 KiB. |
| `run/{run}/{child}` | as `run` | as `run` | as `run` | as `run` | A child holds its own transcript. The parent holds only the child's summary. |
| `archive/{run}` | 5 min. Settled when done. | forever | under 1 KB | under 4 KB | About ten commands per move. |
| `search` | never evicted | none. The SQLite file is derived and can be deleted. | none. It writes through an effect. | none | The database is about 1.5 times the text it indexes. About 6 GB per year at the numbers above. |
| `activity` | never evicted | none | one entry per active run and per connected surface. Under 100 KB. | none | Bounded by concurrent runs and open connections. |
| `live/{run}` | 1 min after the last viewer leaves | one turn for deltas, one connection for presence | one turn's output plus one entry per viewer. Up to a few hundred KBs. | none | Reset at every turn. |
| `tool/{run}/{call}` | never idle. Ends with the call. | one call, which may outlive its turn when backgrounded | a process handle or a surface reference, and 64 KiB of buffered output | none | Output above 64 KiB streams to a blob. Progress is never stored. |

At the numbers above, a deployment holds about 50 loaded runs at a busy
moment, about 60 open event streams, and about 200 MB of actor state in
memory. The hot store grows by about 60 GB of journals and blobs per year
until runs are archived.

## What forever means

The system deletes nothing on its own. A run is kept hot until a person runs
one of two explicit operations:

- **archive** moves the run's journal and blobs to cold storage. The
  catalogue keeps the run's title, project, owner, dates, and final summary.
  The run stays listed and its summary page stays browsable. **restore**
  moves it back.
- **delete** erases the journal and blobs, hot or cold, and records a
  tombstone in the catalogue. The id is never reused.

Nothing compacts a log and nothing snapshots one. That is a known limit of
`actors`, and this design bounds every log by construction instead: budgets
and catalogues by month, runs by the size of a conversation, and subagent
work by giving each child its own run.
