# References

This is a working research index, not a dependency manifest and not a license
verification database.

Only a small set is relevant to BB0/BB1.

## Read now

### Babashka

Repository: `https://github.com/babashka/babashka`

Purpose:

- implementation being derived;
- native build tooling;
- current feature/dependency layout;
- SCI integration;
- class/native-image configuration.

### SCI

Repository: `https://github.com/babashka/sci`

Purpose:

- public context APIs;
- namespace/class exposure;
- `init`/`fork` and related construction mechanisms;
- interruption behavior.

Prefer public APIs. Treat use of `sci.impl.*` as a design smell requiring written
justification.

### GraalVM / Clojure native-image prior art

Repository/documentation family:
`https://github.com/clj-easy/graal-docs`

Purpose:

- native-image build/reachability practices;
- compatibility troubleshooting.

### dvergr

Repository: `https://github.com/replikativ/dvergr`

Purpose:

- bounded SCI evaluation prior art;
- SCI escape/hardening examples;
- useful tests where a denied escape is paired with a positive intended capability.

Do not copy its broader architecture blindly.

### Track A prior art

Repository/PR: `casselc/agent-lisp-starter`, PR #1.

Purpose:

- restricted SCI patterns;
- bounded output/deadline tests;
- SmolVM isolation/mount testing;
- direct Environment→VM command path;
- pod-over-SmolVM feasibility result;
- admission/journal/metric lessons.

Track A is a separate experiment. Study it; do not modify or depend on it.

## Future references — do not deep-read for BB0

- `https://github.com/replikativ/geschichte`
- `https://github.com/babashka/pods`
- `https://github.com/smol-machines/smolvm`
- `https://github.com/cnuernber/charred`
- `https://github.com/mpenet/oda`
- `https://github.com/yogthos/chiasmus`
- `https://github.com/ObneyAI/grain`
- `https://github.com/cedar-policy/cedar`
- `https://github.com/cedar-policy/cedar-for-agents`
- `https://p-org.github.io/P/`
- `https://github.com/quint-co/quint`
- `https://alloytools.org/`
- `https://wiki.event-b.org/`
- `https://lean-lang.org/`
- `https://dafny.org/`
- `https://why3.org/`

## Licensing/provenance rule

Before linking, compiling, vendoring, redistributing, or copying code from any
external project:

1. verify the exact repository/revision;
2. inspect the actual license text for that revision;
3. record required attribution/notices;
4. do not promote `package.json`, README prose, or package-registry metadata to
   "verified license" when an authoritative license artifact has not been inspected.

Keep the active dependency/provenance registry small enough to maintain accurately.
