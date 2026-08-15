# Current Scope

**Active milestone:** BB0 — reproduce and measure upstream baseline.

## BB0 question

Can this branch reproduce a pinned upstream Babashka native build on the implementation
machine with a scripted procedure and enough measurements/provenance to serve as the
baseline for later bb4t changes?

## IN

- record exact upstream tag/commit;
- record local platform/JDK/GraalVM/native-image coordinates;
- inspect and use upstream build tooling;
- create a repeatable build command/script if useful;
- build the unmodified native executable;
- run appropriate upstream/smoke checks;
- record binary digest/size;
- record baseline build/startup/runtime measurements;
- create/update `bb4t.edn` with observed coordinates;
- write `docs/BB0_FINDINGS.md`.

## OUT

Do not implement any of the following during BB0:

- feature/library removal;
- library replacements;
- Geschichte/Datahike/Konserve/Hasch;
- Charred/Oda evaluation;
- capability catalog;
- `ContextSpec`;
- dynamic SCI profiles;
- Chiasmus;
- Prolog or Z3;
- P/Quint/Alloy/Event-B/Lean/Dafny/Why3;
- Cedar;
- Grain integration;
- SmolVM integration;
- Web UI;
- HTTP API/server;
- MCP;
- A2A;
- ACP;
- Mycelium;
- training/evaluation pipeline;
- Track A changes.

## BB0 acceptance

BB0 is accepted when:

- [ ] exact upstream source coordinate is recorded;
- [ ] exact local toolchain/platform coordinate is recorded;
- [ ] a clean native build succeeds;
- [ ] build steps are scripted or otherwise reproducible without undocumented manual steps;
- [ ] binary digest and size are recorded;
- [ ] baseline measurements are written as EDN;
- [ ] selected upstream/smoke tests pass, or failures are explicitly explained;
- [ ] `docs/BB0_FINDINGS.md` clearly states remaining reproducibility gaps;
- [ ] no unrelated product/runtime feature work entered the diff.

## Next milestone

BB1 is authorized only after BB0 review.

BB1 will answer:

> Can one native binary construct multiple declaratively specified SCI contexts with
> materially different, fail-closed authority surfaces?

See `docs/ROADMAP.md`.
