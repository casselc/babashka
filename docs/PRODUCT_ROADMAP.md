# Product Roadmap

This roadmap tracks useful bb4t and bbagent product evolution. Product development
does not wait for the original Track A experiment. Reproducible research is
preserved by pinning exact runtime, agent, model, world, context, surface, prompt,
and policy coordinates for each run.

## Product Sequence

```text
BB0   pinned native baseline
  |
BB1   RuntimeManifest + CapabilityCatalog + ContextSpec + bounded SCI contexts
  |
A0    separate bbagent skeleton and smallest single-agent harness
  |
A1    native TUI + REPL + structured event inspection
  |
A2    useful single-agent coding loop
  |
A3    durable session journal + resume
  |
A4    richer project capabilities
  |
A5    context compiler + explicit memory
  |
A6    work/issues + skills + model routing
```

`bb4t` provides execution: the compiled capability universe, declarative bounded
contexts, semantic operation dispatch, runtime coordinates, and low-level runtime
primitives. The separate `bbagent` application provides agency: model interaction,
agent loops, sessions, context compilation, memory, work, skills, routing, TUI, and
later orchestration.

## Runtime Milestones

Deeper runtime work proceeds in parallel when product evidence demands it:

```text
BB2   deterministic build profiles
BB3   measured runtime curation
BB4   Geschichte/world-history experiment
BB5   SmolVM worker packaging
later Chiasmus / Cedar / richer protocol surfaces
```

Each milestone has a bounded scope and evidence gate. A later capability is not a
reason to enlarge the active milestone.

## Coordinates Over Product Freezes

The product may evolve aggressively. Experiments and durable sessions must name
exact coordinates rather than relying on a moving branch:

```clojure
{:runtime {:bb4t/commit ... :manifest/digest ...}
 :agent {:bbagent/commit ... :profile ...}
 :model {...}
 :world {...}
 :context {:context-spec/digest ...}
 :surface {...}
 :prompt {...}
 :policy {...}}
```

Freeze experimental coordinates, not product development.
