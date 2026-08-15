# AGENTS.md — bb4t bootstrap

This repository is a deliberately minimally divergent Babashka-derived native
runtime. The immediate work is empirical runtime engineering, not implementation of
the full long-term agent platform.

## Read first

Before making changes, read:

1. `docs/CURRENT_SCOPE.md`
2. `docs/VISION.md`
3. `docs/ROADMAP.md`
4. `docs/UPSTREAM.md`
5. `docs/MEASUREMENTS.md`
6. the ADRs under `docs/architecture/`

For BB0, also read `INITIAL_PROMPT.md`.

## Core invariant

Keep these distinct:

```text
BUILD-TIME POSSIBILITY
what the native executable contains

RUN-TIME SCI AUTHORITY
what one SCI context may resolve/invoke

ISOLATION-DOMAIN AUTHORITY
what a containing worker/VM may reach

EXTERNAL EXPOSURE
what is projected to UI/API/MCP/A2A/ACP/etc.
```

Long-term:

```text
requested capabilities
        ⊆
authorized capabilities
        ⊆
compiled runtime capabilities
```

The presence of a library or Java class in the native image must never imply that a
model-facing SCI context can access it.

## Working style

- Prefer the smallest change that answers the current milestone question.
- Preserve upstream Babashka behavior unless the current milestone explicitly
  requires a deviation.
- Prefer public SCI/Babashka APIs over implementation internals.
- Keep persistent manifests/configuration data-only and deterministically
  canonicalizable.
- Record measurements before optimization.
- Add positive and negative tests for every authority boundary.
- Treat an inability to reproduce or explain the build as a defect.
- Do not broaden the current milestone because a future feature looks easy.

## Current scope discipline

`docs/CURRENT_SCOPE.md` is authoritative for the active milestone.

Anything listed under **OUT** must not be implemented unless the scope document is
explicitly revised before the work starts.

In particular, the bootstrap milestone does not include Geschichte, Chiasmus,
Cedar, SmolVM integration, Web UI, MCP/A2A/ACP servers, Mycelium, model training,
or changes to the Track A experiment.

## Evidence

Do not turn a successful build into a broader architectural claim.

A milestone report should distinguish:

- what was demonstrated;
- what was measured;
- what remains unknown;
- what is an inference;
- what would change the current recommendation.

## Upstream divergence

This is a curated distribution/fork, not an independent Babashka implementation.

Prefer a patchset that remains boring to rebase:

```text
+ selected dependencies/features
- measured unused features
± measured replacements
+ native-image metadata
+ bb4t runtime/catalog code
+ compatibility/authority tests
```

Avoid rewriting unrelated evaluator, process, dependency, or REPL semantics.
