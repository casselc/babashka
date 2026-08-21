# Current Scope

**Active milestone:** none. A1.1 is closed; A2 is not open.

**Milestone status:** BB0 PASS, BB1 PASS, A0 PASS, S0a PASS, S0b PASS, A1 PASS,
A1.1 PASS.

## Purpose

S0a is complete, accepted, and frozen at `bb4t-s0a`. Its result is integrated into
`bb4t/dev` by fast-forward and recorded in `docs/S0A_SQLITE_NATIVE_BUILD.md`. The
accepted design stands: the ordinary bb4t build does **not** include SQLite merely
because bbagent does, and the pinned `next.jdbc` and `sqlite-jdbc` dependencies plus
the native sqlitejdbc sidecar remain confined to the inactive-by-default
`:app/bbagent` application profile.

A1 is complete; `:app/bbagent` gained one pinned dependency, `charm.clj 0.2.74`,
and nothing else changed.

A1.1 is complete, accepted, and frozen in the separate `bbagent` repository at
`bbagent-a1.1`. **It required no bb4t change, as this scope predicted.** It
derived model orientation from the existing RuntimeCatalog and Context
description rather than adding capability, so there is no new bb4t coordinate;
bb4t's contribution remains the S0a application build profile and the A1
`charm.clj` pin. bb4t's role stays limited to hosting the application build
profile. The accepted BB1 result remains frozen at `bb4t-bb1` and the A0
application hook at `bb4t-a0`.

**What A1.1 found that bb4t should expect.** Orientation is generated from the
context description, so a capability added to the RuntimeCatalog appears in the
model's preamble automatically. The *absence* of capability is not: bbagent
currently asserts it as prose. Closing that needs the context description to
answer "which effects does this context grant" in a form bbagent can project,
which is a description/projection question rather than a new capability. If
bbagent cannot answer it from what the description already exposes, that is the
one place A2 may legitimately ask bb4t for something, and it must still be a
projection of existing authority, never a widening of it.

## Owned Work

- keep `:app/bbagent` the single application-specific build seam;
- add a pinned TUI dependency to `:app/bbagent` only if the A1 runtime spike selects
  a library rather than vendored source, together with any reachability metadata the
  native proof actually requires;
- preserve ordinary bb4t dependency and SCI surfaces unchanged.

## Explicit Exclusions

- no changes to Track A, BB0, BB1, A0, or S0a evidence history;
- no runtime, context, catalog, kernel, or semantic operation changes;
- no new capability, ContextSpec, profile, or SCI projection;
- no TUI, JLine, Charm, SQLite, JDBC, or next.jdbc namespace, class, or operation in
  bounded SCI;
- no generalized BB2 build-profile or BuildManifest implementation;
- no generalized production/developer/evidence/worker profile split, which remains
  deferred BB2 work.

## Dependencies And References

- immutable BB1 coordinate `bb4t-bb1`;
- immutable A0 application hook coordinate `bb4t-a0`;
- immutable S0a coordinate `bb4t-s0a`, implementation
  `f438307280b7a01fd20e99f54cd82682ef15d12a`;
- `next.jdbc` `1.3.1118` and `sqlite-jdbc` `3.53.2.1`;
- JLine `4.3.1`, already built with `-H:+ForeignAPISupport`,
  `-H:+SharedArenaSupport`, and `--enable-preview`.

## Stop Gate

Any bb4t change requested by A1 must be independently reviewable and must prove its
authority surface. TUI convenience does not justify widening model-facing authority.
