# Future Seams — Not Current Requirements

This document exists to prevent bootstrap work from accidentally closing important
doors. It is **not** an implementation backlog for BB0/BB1.

## Worker/isolation

- SmolVM worker distribution;
- explicit RW project and RO shared/runtime mounts;
- no ambient network/credentials;
- hard VM cleanup boundary;
- retained in-VM pod/project runtimes.

## Human and protocol surfaces

Potential projections over the same semantic capability kernel:

```text
Web UI
HTTP/API
MCP
A2A
ACP
pod
trusted nREPL/dev access
```

A future Portal-like UI may use `tap>`, `datafy`, and `nav` to inspect values, traces,
agent state, evidence, and control loops.

Every surface must route through the same principal/authorization/capability path.

## Project/world history

Geschichte is a candidate trusted host implementation for project/world history if
its native-image dependency closure remains manageable.

Do not expose raw Geschichte/Datahike merely because they are compiled.

## Reasoning

Long-term Agent REPL shape:

```text
code/*   logic/*   formal/*
   \       |       /
          Chiasmus
          /      \
       Prolog     Z3
           \      /
      external formal workers
```

Candidate formal backends include P, Quint, Alloy, Event-B, Lean, Dafny, and Why3.

These should normally be semantic capability workers, not all compiled into the main
runtime.

## Policy

Cedar is a candidate authorization plane attached to topology.

Conceptually:

```text
Cedar authorization
∩ ContextSpec/SurfaceSpec
∩ compiled capability
∩ reachable world
=
actual actuation
```

Do not make Cedar a BB0/BB1 dependency.

## Self-describing project runtimes

Grain is useful prior art for:

```text
catalog
→ schema
→ validate
→ invoke
→ events
→ projections
→ diagnostics
```

A later adapter should start read-only and preserve the same authorization boundary.

## Delegation

Mycelium/Track B may later compile delegated authority/resources/budgets into
ContextSpecs and worker placement.

Keep separate:

```text
Agent
Cell
Context
Worker
```

## Track A

Do not change Track A v0.

After its first reported result, a separately frozen replication may run both
conventional and persistent-SCI arms on the same pinned bb4t runtime.
