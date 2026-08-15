# Current Scope

**Active milestone:** BB1 - bounded declarative SCI contexts.

## Purpose

Answer one question:

> Can one pinned native bb4t binary construct multiple declaratively specified SCI
> contexts with materially different, fail-closed authority surfaces?

BB1 demonstrates semantic capability restriction within the runtime. It does not
prove hostile-code filesystem or process isolation.

## Owned Work

- data-only `RuntimeManifest`, `CapabilityCatalog`, `CapabilitySpec`, and
  `ContextSpec` descriptions;
- a separate trusted implementation registry and live `Context` objects;
- explicit `SemanticOperation` dispatch;
- deterministic canonicalization and SHA-256 coordinates;
- fresh SCI contexts built additively from a minimal safe base;
- profiles `:agent/minimal`, `:transform/pure`, and `:agent/project-read`;
- one root-contained, read-only `project/read` semantic capability;
- a small application-facing describe/create/invoke/events seam;
- structured in-memory operation events, not a durable journal;
- positive and negative authority tests on JVM and native paths;
- BB1 measurements, machine-readable evidence, and findings.

## Required Outputs

- BB1 runtime/catalog/context/operation/event code;
- executable canonicalization and authority test vectors;
- `artifacts/bb1-*.edn` evidence;
- `docs/BB1_FINDINGS.md` with a proceed, revise, or stop recommendation;
- a fresh review of authority attenuation, fail-closed behavior, canonical/live
  separation, deterministic coordinates, native parity, fork debt, and client seam.

## Dependencies And References

- immutable BB0 coordinate `bb4t-bb0`;
- Babashka `v1.13.219`, commit
  `140ef9dcd770a54457a02fa29c3a2f643f4968d4`;
- SCI commit `64163c4560e085ffdcf47951b406f62f753b7f4c`;
- `docs/VISION.md`, `docs/ROADMAP.md`, and ADRs under `docs/architecture/`;
- only libraries already present in the BB0 compiled runtime.

## Explicit Exclusions

- no changes to the original Track A experiment;
- no bbagent application or TUI;
- no durable session journal, resume, memory, skills, model loop, or routing;
- no runtime slimming, build profiles, or library replacement;
- no new Geschichte, Datahike, Chiasmus, Cedar, Charm, or other runtime dependency;
- no SmolVM, worker, filesystem/process sandbox, or hostile-code isolation claim;
- no HTTP, MCP, A2A, ACP, Web UI, or generalized projection framework;
- no BB2 or later milestone work.

## Stop Gate

Stop after BB1 findings and fresh review. Repair BB1 if the semantic kernel is not
clean enough for an external bbagent client; do not build bbagent during this scope.
