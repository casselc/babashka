# ADR 0003: Construct SCI Contexts Dynamically From a Minimal Safe Base

**Status:** Proposed

## Context

`bb4t` should support multiple differently authorized computational contexts from the
same compiled executable.

Attempting to create one maximal SCI context and remove authority afterward is harder
to reason about and easier to get wrong.

## Decision

Construct contexts from a minimal safe base, then add explicitly approved capability
bundles.

Conceptual API:

```clojure
(bb4t.context/create
 runtime
 {:profile :agent/project-read
  :grant #{:core/pure
           :project/read}
  :limits {...}})
```

Unknown capabilities fail closed.

Model-facing code must not receive unrestricted authority-widening context APIs.

The initial proof should use only capabilities already available in the upstream
runtime. Do not add third-party libraries simply to exercise the mechanism.

## Acceptance idea

Demonstrate three contexts with materially different positive/negative matrices while
sharing the same runtime binary.

## What would change our mind?

Reconsider if:

- fresh context construction is too expensive for intended usage;
- SCI APIs cannot reliably prevent ungranted host/JVM access;
- context behavior differs materially between JVM tests and native image;
- authority metadata cannot remain deterministic/data-only.
