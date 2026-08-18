# Current Scope

**Active milestone:** S0a - bbagent custom native SQLite inclusion spike.

## Purpose

Answer one question:

> Can the custom bb4t + bbagent native application include and reliably use a
> JNI-backed SQLite driver while bounded BB1 SCI authority remains unchanged?

The accepted BB1 result remains frozen at `bb4t-bb1`. S0a changes only the
application-specific native build profile and must not reinterpret BB1 evidence or
RuntimeManifest semantics.

## Owned Work

- add pinned `next.jdbc` and `sqlite-jdbc` dependencies only to `:app/bbagent`;
- preserve ordinary bb4t dependency and SCI surfaces;
- preserve the existing BB1 catalog, ContextSpec, runtime, and operation semantics;
- build and measure an exported sqlitejdbc sidecar with the bbagent executable;
- record separate source, dependency, toolchain, executable, and sidecar evidence.

## Required Outputs

- a source-pinned bbagent application image and sqlitejdbc sidecar;
- JVM and native SQLite smoke evidence;
- unchanged-authority evidence using the actual bounded A0 Context;
- `docs/S0A_SQLITE_NATIVE_BUILD.md` with the bb4t-side result and limitations.

## Dependencies And References

- immutable BB1 coordinate `bb4t-bb1`;
- immutable A0 application hook coordinate `bb4t-a0`;
- pinned bbagent source selected by its application build wrapper;
- `next.jdbc` `1.3.1118` and `sqlite-jdbc` `3.53.2.1`.

## Explicit Exclusions

- no changes to Track A, BB0, BB1, or A0 evidence history;
- no SQLite, JDBC, or next.jdbc namespace/class/operation in bounded SCI;
- no changes to bb4t runtime, context, catalog, kernel, or semantic operations;
- no generalized BB2 build-profile or BuildManifest implementation;
- no static SQLite linkage or cross-platform packaging claim;
- no bbagent journal migration, TUI, memory, search, editing, process, or multi-agent work.

## Stop Gate

Stop after source-pinned native evidence and review. Do not begin bbagent storage
integration automatically.
