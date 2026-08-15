# ADR 0001: Curated Babashka-Derived Runtime

**Status:** Proposed

## Context

The desired agent runtime benefits from Babashka's native startup, SCI integration,
process/filesystem ecosystem, and Clojure data model, but may eventually need a
different compiled feature/library universe from stock Babashka.

Examples include:

- removing unused stock features;
- embedding selected native-image-compatible libraries;
- exposing multiple bounded SCI contexts;
- producing stripped worker and richer developer/server distributions.

## Decision

Use Babashka as the upstream implementation and keep `bb4t` a deliberately minimally
divergent derived distribution.

Prefer additions/removals that can be explained as runtime curation rather than
rewriting Babashka semantics.

## Consequences

Positive:

- proven upstream runtime/build;
- easier access to Babashka/SCI ecosystem;
- native executable from the start;
- clear upstream-diff metric.

Risks:

- fork maintenance;
- native-image metadata maintenance;
- accidental divergence;
- temptation to embed too much.

## What would change our mind?

Reconsider if:

- required changes repeatedly demand large Babashka-internal rewrites;
- upstream rebasing becomes disproportionate to runtime value;
- an overlay/upstream extension mechanism can express the same runtime cleanly;
- native-image maintenance makes the desired library universe impractical.
