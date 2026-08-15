# BB0 Initial Agent Prompt

You are starting `bb4t`, a deliberately minimally divergent Babashka-derived native
runtime.

Do **not** implement the full vision.

Your first job is **BB0: reproduce and measure an unmodified native build of the
pinned upstream Babashka baseline on this machine**.

Read, in order:

- `AGENTS.md`
- `docs/CURRENT_SCOPE.md`
- `docs/VISION.md`
- `docs/UPSTREAM.md`
- `docs/MEASUREMENTS.md`
- `docs/architecture/0001-curated-babashka-runtime.md`
- `docs/architecture/0002-compiled-vs-authorized.md`
- `docs/architecture/0003-dynamic-contexts.md`

Then inspect the actual upstream Babashka build tooling and repository state rather
than inventing a parallel build system.

## BB0 required outputs

Produce:

1. the exact upstream Babashka tag/commit coordinate used as the baseline;
2. exact JDK/GraalVM/native-image/platform coordinates;
3. a scripted, repeatable native build procedure;
4. the native binary and its digest;
5. baseline measurements in a machine-readable EDN artifact following
   `docs/MEASUREMENTS.md`;
6. enough smoke/upstream test evidence to establish that the local native build
   behaves like the selected upstream baseline;
7. a short `docs/BB0_FINDINGS.md` containing:
   - what worked;
   - exact commands;
   - measurements;
   - deviations from upstream, expected to be none or mechanically necessary;
   - remaining reproducibility gaps;
   - recommendation for whether BB1 should proceed.

## Do not do in BB0

Do not:

- remove stock libraries or features;
- replace Cheshire or any other library;
- add Geschichte/Datahike;
- change the SCI surface;
- implement the capability catalog;
- implement `ContextSpec`;
- rename or productize the binary if that complicates baseline comparison;
- add Chiasmus, Z3, Prolog, Cedar, Grain, P, Quint, Alloy, Lean, or Dafny;
- add Web UI, HTTP server, MCP, A2A, ACP, or nREPL features;
- add SmolVM integration;
- change Track A;
- begin BB1 before BB0 is reviewed.

The desired BB0 result is intentionally boring:

```text
pin → build → test → measure → record
```

If the upstream native build cannot be reproduced cleanly, stop and report that
rather than masking the problem with unrelated changes.
