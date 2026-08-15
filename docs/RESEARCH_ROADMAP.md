# Research Roadmap

Research evolution is separate from product development. The original Track A
apparatus and its first result remain untouched by bb4t and bbagent work.

## Sequence

```text
Track A original experiment
  -> report frozen result
  -> later replication on pinned bb4t/bbagent
  -> scoped-context experiments
  -> persistent-world experiments
  -> Track B recursive delegation
```

The later bb4t/bbagent study is a replication or new experiment, not a retroactive
replacement for Track A. Where possible, experimental arms should share the same
pinned implementation and vary only the intended surface or treatment:

```clojure
{:experiment/arm :control
 :runtime ...
 :agent ...
 :surface :conventional-tools}

{:experiment/arm :repl
 :runtime ...
 :agent ...
 :surface :persistent-lisp}
```

## Evidence Rule

Every experiment must retain enough runtime, agent, model, world, context, surface,
prompt, and policy coordinates to reconstruct its effective environment. Reports
must distinguish measured results from inference and state which coordinate changes
would invalidate comparison.

Raw structured trajectory and event history should remain authoritative whenever
practical. Summaries, memories, metrics, training examples, and UI views are derived
projections.

## Boundaries

- Product work must not modify or depend on the original Track A apparatus.
- Track A completion does not gate bb4t or bbagent product development.
- Track B delegation remains future work and does not enlarge BB1 or early bbagent.
- Future experiments pin released commits/manifests rather than treating product
  branch names as stable coordinates.
