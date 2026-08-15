# ADR 0002: Compiled Availability Is Not Runtime Authority

**Status:** Proposed

## Context

A native image may contain libraries/classes that trusted host code needs while an
untrusted/model-facing SCI context should see only a small semantic surface.

Equating "compiled into the executable" with "available to SCI" would turn native
reachability into ambient authority.

## Decision

Maintain distinct representations for:

```text
compiled library/class availability
semantic capability availability
context/principal authorization
SCI namespace/class projection
external surface exposure
```

The long-term invariant is:

```text
requested
  ⊆ authorized
  ⊆ compiled
```

and actual actuation may be further constrained by isolation-domain reachability.

Prefer exposing trusted libraries through narrow semantic Vars/facades rather than
raw implementation namespaces/classes.

## Consequences

This adds catalog/context machinery but creates a stable boundary for:

- multiple SCI profiles;
- stripped/full builds;
- SmolVM workers;
- policy engines;
- Web/API/MCP/A2A/ACP projections;
- later delegation systems.

## What would change our mind?

Reconsider if SCI cannot construct these bounded surfaces fail-closed using a small
maintainable mechanism, or if the abstraction requires pervasive implementation
forks.
