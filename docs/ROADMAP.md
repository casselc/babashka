# bb4t Runtime Roadmap

This document tracks bounded runtime milestones. Product and research evolution are
kept separate in `docs/PRODUCT_ROADMAP.md` and `docs/RESEARCH_ROADMAP.md`.

## BB0 — upstream baseline

Goal:

```text
pin → build → test → measure → record
```

No product innovation.

Exit artifact: reproducible upstream native build + baseline EDN + findings.

## BB1 — capability catalog + ContextSpec

Goal:

> Prove the central bb4t runtime hypothesis.

Implement the smallest trusted data model for:

- compiled library availability;
- semantic capabilities;
- SCI namespace/class projection;
- effects/purity;
- limits;
- provenance.

Construct at least three materially different contexts from a minimal safe base:

```text
:agent/minimal
:transform/pure
:agent/project-read
```

Add positive and negative authority tests.

Do not add new third-party runtime libraries merely to make the catalog interesting.

## BB2 — declarative build profiles

Introduce deterministic build-profile metadata.

Candidate conceptual profiles:

```text
:baseline
:worker
:full
:dev
:gateway
:custom
```

Initially, two real variants are enough.

Resolve build capabilities to feature/dependency/native-image closure and emit a
runtime manifest/digest.

## BB3 — measured curation

Only after BB1/BB2:

- remove clearly unused stock features;
- measure binary/startup/build/security-surface deltas;
- evaluate replacements only when they improve the total runtime tradeoff.

Potential later JSON/CSV bakeoff:

```text
Cheshire baseline
Charred
Oda if toolchain-compatible
```

Do not replace libraries based on novelty or microbenchmarks alone.

## BB4 — first genuinely new embedded library

Use Geschichte as the first meaningful native-embedding experiment if its current
native-runtime support still makes it a good candidate.

Required evidence:

```text
JVM reference behavior
→ bb4t native host behavior
→ narrow SCI semantic facade
→ negative raw-library authority tests
```

Stop if native-image maintenance becomes broad or brittle.

## BB5 — worker packaging

Package a stripped worker build into SmolVM.

Reuse/adapt proven isolation ideas:

- explicit RW workspace;
- explicit RO shared/runtime content;
- network off by default;
- no ambient host home/secrets/sockets;
- hard VM stop/delete boundary.

## BB6 — richer surfaces and reasoning

Only after the runtime/capability boundaries are stable:

- Portal-like `tap>` / `datafy` / `nav` inspection;
- HTTP/API;
- MCP/A2A/ACP;
- Chiasmus + Prolog/Z3;
- external formal backends;
- Cedar policy projection;
- richer project-runtime/pod integrations;
- Track A replication on pinned bb4t;
- Mycelium-to-ContextSpec integration.

Each should be its own bounded milestone rather than one "platform" phase.
