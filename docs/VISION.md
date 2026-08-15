# bb4t — Trimmed Runtime Vision

**Status:** bootstrap vision  
**Project name:** bbagent  
**Runtime / executable name:** `bb4t`

## Thesis

`bb4t` is a curated Babashka-derived native Clojure runtime intended to provide a
rich **trusted compiled capability universe** while constructing narrowly bounded
SCI contexts dynamically at runtime.

The fundamental separation is:

```text
BUILD TIME
what this executable can possibly do

RUN TIME
what this particular context/principal is allowed to do
```

The core set relationship is:

```text
requested context capabilities
        ⊆
authorized capabilities
        ⊆
compiled runtime capabilities
```

The fact that a library/class is compiled into `bb4t` must not imply that a
model-facing SCI context can access it.

## Four independent boundaries

Keep these separate:

1. **Compiled possibility** — libraries/classes/features physically in the native
   executable.
2. **SCI/context authority** — Vars/classes/semantic capabilities exposed to one
   context.
3. **Isolation-domain authority** — filesystem/network/process/world reachable by
   the worker/VM.
4. **External exposure** — capabilities projected through Web/API/MCP/A2A/ACP/pod/
   nREPL or other surfaces.

This separation should remain visible in manifests and tests.

## Target architecture

```text
                     bb4t native runtime
┌─────────────────────────────────────────────────────┐
│ curated compiled library/capability universe        │
│                                                     │
│ SCI / core.async / selected libraries / pod client  │
│ runtime/catalog/context construction                │
└───────────────────────┬─────────────────────────────┘
                        │
              Runtime Capability Catalog
                        │
              policy + ContextSpec
                        │
          ┌─────────────┼─────────────┐
          ▼             ▼             ▼
     SCI context    SCI context   SCI context
       minimal       project        pure data
```

Later, semantic capabilities may be implemented locally, through pods, or through
isolated project runtimes. Protocol/UI surfaces should project the same semantic
operations rather than define separate authority paths.

## Initial proof

The first load-bearing bb4t milestone is not "add many libraries."

It is:

> **Demonstrate that the same native executable can construct materially different,
> fail-closed SCI contexts from declarative data.**

The first useful matrix should look approximately like:

```text
                              minimal   pure-data   project-read
(+ 1 2)                          yes        yes           yes
persistent def                   yes        yes           yes
JSON/data helper                  no        yes           yes
approved project/read             no         no           yes
raw slurp                         no         no            no
System/getenv                     no         no            no
Runtime/getRuntime                no         no            no
Class/forName                     no         no            no
raw process                       no         no            no
context mutation APIs             no         no            no
```

Exact capabilities may change as implementation reveals upstream realities.

## Curated distribution, not maximal runtime

`bb4t` may eventually:

- remove stock Babashka features that have low value for this runtime;
- replace stock libraries when total native-runtime tradeoffs justify it;
- embed foundational libraries such as Geschichte if native-image integration
  remains boring;
- produce full, worker, gateway, developer, and custom builds;
- support self-describing `tap>` / `datafy` / `nav` inspection;
- expose selected capabilities through Web/API/MCP/A2A/ACP;
- host Chiasmus-facing code/logic/formal capabilities;
- enforce enterprise authorization through a policy layer such as Cedar;
- run specialized workers through pods and SmolVM.

None of those are bootstrap requirements.

## Runtime placement rule

Prefer:

```text
small compiled universe
+
strong trusted host implementations
+
tiny explicit SCI projections
```

over:

```text
large ambient runtime
+
denylist-based sandboxing
```

For substantial libraries prefer:

```text
trusted host library
      ↓
small semantic facade
      ↓
SCI Var / capability
```

rather than exposing entire implementation namespaces or raw Java classes.

## Reproducibility

Every meaningful build should eventually be described by a deterministic manifest:

```clojure
{:runtime/digest ...
 :upstream/commit ...
 :build/toolchain ...
 :compiled/libraries ...
 :compiled/capabilities ...
 :removed-features ...
 :replacements ...}
```

Every context should have a deterministic coordinate derived from the runtime
manifest plus its ContextSpec/policy inputs.

## Research relationship

Track A tests whether a persistent Lisp interface changes agent behavior. `bb4t`
should not contaminate the first Track A result.

Track B/Mycelium may later compile delegated authority/resources/budgets into bb4t
ContextSpecs and worker placement. bb4t should enforce runtime authority; Mycelium
should continue to own delegation semantics.

The architecture must keep these objects distinct:

```text
Agent    = model-driven controller
Cell     = contracted delegated work
Context  = authorized computational environment
Worker   = physical execution/isolation unit
```
