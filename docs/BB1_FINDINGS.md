# BB1 Findings

**Implementation coordinate:** `19512480f21bd9095d693dcd395f050f18c2d914`
**Result:** Pass, independent fresh review accepted
**Scope:** semantic capability restriction inside one pinned bb4t runtime

## Answer

One pinned native bb4t binary constructed three declaratively specified, persistent
SCI contexts with materially different authority surfaces:

```text
                          minimal   pure-data   project-read
core arithmetic/data        yes        yes          yes
persistent def              yes        yes          yes
bounded JSON helper          no        yes          yes
project/read                 no         no          yes
raw slurp/process/JVM        no         no           no
context construction API     no         no           no
```

The normalized executable JVM and native corpora agreed on all 96 cells: 12 allowed
and 84 denied. They also agreed on 40 packaged assurance checks covering malformed
specs, authority widening, embedded provenance, direct dispatch, canonical data,
bounded events, every implemented JSON bound, opaque value/handle projection,
forged-handle rejection, and project-root containment.

## Implementation

The canonical side is inert data:

- `RuntimeManifest` records the pinned full upstream Babashka compiled universe,
  upstream/SCI/build source coordinates, BB1-catalogued libraries, compiled semantic
  capabilities, and the complete base SCI authority policy;
- `CapabilityCatalog` contains three `CapabilitySpec` values and three
  `SemanticOperation` descriptions;
- trusted `ContextSpec` data resolves against a fixed profile maximum;
- canonical tagged EDN trees produce domain-separated SHA-256 coordinates.

The live side is separate:

- private `RuntimeState` owns implementation functions, real resource paths, event
  atoms, and subscribers;
- private `ContextState` owns the SCI context, evaluation lock, and instance identity;
- clients receive identity-only runtime/context handles backed by private weak
  registries; locked lookup compares keys by object identity rather than
  caller-controlled equality, so record fields and the SCI evaluator are not
  map-accessible;
- bounded SCI Vars capture a context and call the same checked semantic dispatcher
  as trusted host clients;
- no live object is accepted by canonicalization or returned as an evaluated or
  operation value.

The full stock Babashka baseline remains build-time possibility. Cheshire is the
only library catalogued by BB1 because it backs the bounded JSON operations. This
catalogue is not an inventory of everything in the binary, and the presence of any
stock namespace or class does not grant it to a bounded SCI context.

The three profiles are additive fresh `sci/init` contexts. They never fork or reuse
Babashka's normal unrestricted context. A positive symbol allowlist controls built-in
Vars. Pinned SCI exempts non-built-in Vars from that allow check, so BB1 explicitly
denies the observed 18-Var implicit default surface, including both dotted and slash
spellings for protocol maps, `*ns*`, reader state, and `clojure.walk/macroexpand-all`.
BB1 also supplies public `:classes` overrides with `{:class nil :closed true}` for all
18 default JVM classes and an explicit interop deny set. Data-driven default probes
and constructor/method probes verify these boundaries on JVM and native.

Each profile identifier is an explicit authorization preset. Omitted requested and
authorized capability sets materialize that profile's maximum; callers can supply
subsets to attenuate it, but selecting a profile is not an empty-authority default.

`project/read` receives no caller-selected filesystem root. A trusted runtime binds
logical resource `:project/root`; ContextSpec can only request that logical binding.
Reads traverse relative path components from an opened `SecureDirectoryStream`, use
`NOFOLLOW_LINKS`, require a regular file, enforce a byte cap while reading, and
strictly decode UTF-8. Final and intermediate symlinks are denied. Filesystems
without secure directory traversal fail closed.

The application-facing host seam is deliberately small:

```text
runtime/create, runtime/describe, runtime/catalog
context/create, context/describe, context/evaluate
operation/invoke
events/subscribe, events/snapshot
value/describe
```

A fixed `--bb4t-internal` command runs evidence operations in the standalone jar and
native image. The corpus is intentionally compiled into the BB1 artifact so the exact
shipped native executable can run the same vectors as the JVM artifact. This
milestone-only evidence surface creates temporary fixtures only when explicitly
invoked; it is not an application-facing API, and `bb4t.*` host namespaces are not
projected into ordinary Babashka SCI or bounded contexts.

Build provenance is generated into `META-INF/babashka/bb4t-commit` from the exact
fetched source commit before the standalone jar and native executable are built.
The tracked `development` resource marks unmeasured source-tree builds; the exact-SHA
builder replaces it. A missing or blank resource fails only when the BB1 runtime is
created, not when ordinary Babashka starts. `runtime/create` does not accept a
caller-supplied commit.

Evaluation and direct operation invocation return bounded `ValueDescription` data:
small canonical values may include inert data, large canonical values are summarized,
and unsupported host objects or values beyond the description depth/node budget
expose only an opaque type name. Direct host operations therefore summarize strings
over 4,096 canonical characters rather than returning their content. The JSON
capabilities accept only integer numbers because floating-point values are outside
that canonical output domain; fractional JSON is rejected consistently on read and
write. Implementation exceptions are normalized to `:bb4t/error :operation-failed`;
validation and authorization retain their specific categories.

Event sequence allocation, compact bounded retention, and the exact runtime dropped
count share one atomic state transition, including under concurrent context
evaluation. Context snapshots return only currently retained matching events rather
than misreporting the runtime-global dropped count. Subscriber callbacks remain
best-effort diagnostic delivery rather than a durable ordered log.

## Public API Use

BB1 uses only public `sci.core` APIs:

```text
init
eval-string*
create-ns
new-var
new-macro-var
binding
```

No BB1 namespace requires `sci.impl.*`, accesses SCI environment atoms, or uses
private Vars. SCI implementation source was inspected to understand unavoidable
defaults, but those observations are enforced by behavior tests rather than treated
as an implementation API.

## Deterministic Coordinates

```text
runtime  sha256:659a5f3f3bb0e68b4707cb7810d7652e64af78594b77f54fef62e7c36bdb5972
catalog  sha256:f132b513c4492e9cc9e22af088182d03d28b2059eab5c182dcbdf1db6e425f31
vector   sha256:2c1e6b7c6f844c15a7f6a67b0828b0fdc6d38c4fe6d436275bc802471c776d48
```

The canonical domain accepts only nil, booleans, strings, characters, keywords,
symbols, mathematical integers, vectors, lists, sets, and maps. It rejects metadata,
floating-point/ratio ambiguity, records, Vars, functions, Classes, atoms, lazy
sequences, Paths, and other host objects.

Map/set insertion order does not change coordinates. A changed effective grant or
base authority policy does. Host `*print-length*` and `*print-level*` bindings do not
change coordinates. Context coordinates exclude live SCI state, event data,
timestamps, and instance identity. An effective project-read coordinate intentionally
includes the resolved authorized root because changing that root changes authority;
that coordinate is consequently environment-specific rather than rebuild-stable.

## Native And JVM Evidence

The BB1 build fetched the exact published implementation SHA directly, with a full
commit graph and filtered blobs, then checked out shallow pinned submodules. It
verified BB0/upstream ancestry and SCI
`64163c4560e085ffdcf47951b406f62f753b7f4c`. The command remains valid after the
`bb4t/bb1` branch advances because source resolution does not use the branch tip.
The standalone JVM jar and native executable emitted equal normalized parity
payloads.

Focused JVM tests:

```text
17 tests, 163 assertions, 0 failures, 0 errors
```

Full native upstream plus BB1 main phase:

```text
364 tests, 1143 assertions, 0 failures, 0 errors
```

The remaining native phases also passed: flaky 15/23, preloads 1/1, preload
location 1/1, classpath environment 1/1, pod 1/2, and socket REPL 3/18.

The full upstream JVM suite is not a BB1 gate and no result for it is claimed here.
The focused JVM suite covers BB1 directly; the complete upstream-plus-BB1 suite is
run against the authoritative native artifact, including its pod-backed paths.

## Measurements

Against the recorded BB0 baseline:

```text
BB0 binary             87,296,256 bytes
BB1 binary             88,213,760 bytes
delta                     917,504 bytes (+1.051%)
BB1 SHA-256             f5bbc65bfd01a3f9d50e66ec5fa91f0ec25f659100e26ebbf74b3630af764971

BB0 build wall             72.140 s
BB1 build wall             79.950 s
delta                       7.810 s (+10.83%)
```

The BB0 timing was measured on the host while BB1 used the pinned container, so the
build-time delta is directional rather than a controlled performance claim.

Native context construction after five warmups, 30 samples per profile:

```text
profile                 median       p95
agent/minimal           0.182 ms    0.193 ms
transform/pure          0.209 ms    0.496 ms
agent/project-read      0.270 ms    0.277 ms
```

The catalog has 3 capabilities and 3 operations. Capability projections contain
0/0, 1/2, and 2/3 namespace/Var counts for minimal, pure, and project-read. Including
the base `user` namespace with `apropos` and `doc`, total projected surface counts
are 1/2, 2/4, and 3/5. No Java class is projected.

From `bb4t/dev`, the measured BB1 coordinate changes 24 files with 2,771 insertions
and 9 deletions across eighteen commits. From the immutable BB0 tag, product-roadmap
and BB1 work together change 29 files with 2,969 insertions and 82 deletions across
nineteen commits.

Machine-readable values are in `artifacts/bb1-measurements.edn` and
`artifacts/bb1-authority.edn`. Raw logs, corpora, measurements, and the binary remain
under ignored `.bb1-ci-artifacts/` locally.

`PACKAGE_MANIFEST.json` remains the immutable bootstrap delivery manifest described
by the BB0 provenance record. Its checksums intentionally describe the delivered
starter package, not the current BB1 working tree.

## Fresh Review

Independent Kimi K3 runtime/authority and DeepSeek V4 Flash evidence reviews both
returned `ACCEPT` for the final measured implementation and refreshed evidence. An
initial Kimi review identified alternate slash spellings for four SCI protocol Vars;
the measured implementation explicitly denies and probes both dotted and slash
spellings across all profiles on JVM and native.

A subsequent pull-request review found that `subvec` retained historical event
storage, provenance validation ran too early, fractional JSON became opaque on return,
and context snapshots reported a runtime-global drop count. The final measured
implementation compacts retained events, defers missing-provenance failure to BB1
runtime creation, rejects fractional JSON on both read and write, and omits the
misleading context drop field.

## Security And Isolation Limits

BB1 demonstrates semantic capability restriction within the runtime. It does not
prove hostile-code filesystem/process isolation.

Specifically, BB1 does not establish:

- an operating-system, process, container, or VM sandbox;
- resistance to all SCI implementation defects;
- isolation from host resources reachable through a future trusted implementation;
- hostile concurrent filesystem mutation beyond secure relative traversal,
  including replacement of the already-resolved project-root directory;
- network, CPU, memory, deadline, or process isolation;
- safety from accidental unbounded computation by a buggy script; allowed sequence
  operations can still hang a caller or exhaust the containing process;
- bounded stdout/stderr capture during evaluation;
- authorization policy correctness beyond the trusted ContextSpec decision;
- durable event integrity, confidentiality, total subscriber ordering, or replay;
- bit-for-bit rebuild reproducibility while apt and Maven/Clojars content remain
  version-selected but not fully content-locked.

The minimal profile deliberately omits general function construction and broad flow
forms such as `fn`, `loop`, and `try`; later profile work must widen that surface
explicitly rather than inheriting normal Clojure defaults.

Hard isolation remains a later worker/SmolVM boundary. SCI is not described as a
complete security sandbox.

## Required Questions

1. **Public APIs only?** Yes. BB1 uses the public SCI APIs listed above.
2. **Internals required?** No. Internal source was read, not called.
3. **Fail closed?** Yes for tested catalog/spec/profile/resource/surface inputs;
   unknown or widening inputs fail before context construction.
4. **Canonical manifests free of live objects?** Yes, enforced by rejection tests.
5. **Deterministic coordinates?** Yes for the defined canonical domain and vectors.
6. **Native matches JVM?** Yes for the normalized 96-cell corpus and 40 checks.
7. **Fork divergence?** 24 files, +2,771/-9 from `bb4t/dev` at the evidence commit.
8. **Claims not established?** OS/hostile-code isolation and the limits above.
9. **Clean external-client seam?** Yes; fresh reviews accepted the façades, which
   return bounded descriptions/data and do not expose mutable evaluator objects.
10. **Proceed, revise, or stop?** Stop at the accepted BB1 gate as required by the
    current scope. Do not start bbagent without a separately authorized scope.
