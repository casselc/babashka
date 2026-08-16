# BB1 Findings

**Implementation coordinate:** `b9420f0a347dc64b0a10b880fbc9914f6de537a9`
**Result:** Pass, proceed to fresh BB1 review
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

The executable JVM and native corpora agreed on all 51 cells: 12 allowed and 39
denied. They also agreed on 32 packaged assurance checks covering malformed specs,
authority widening, embedded provenance, direct dispatch, canonical data, bounded
events, every implemented JSON bound, opaque value/handle projection, forged-handle
rejection, and project-root containment.

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
Babashka's normal unrestricted context. A positive symbol allowlist controls Vars.
Pinned SCI also installs default JVM classes outside that allowlist, so BB1 supplies
public `:classes` overrides with `{:class nil :closed true}` for all 18 defaults and
an explicit interop deny set. Constructor and direct-method negative probes verify
that boundary on JVM and native.

`project/read` receives no caller-selected filesystem root. A trusted runtime binds
logical resource `:project/root`; ContextSpec can only request that logical binding.
Reads traverse relative path components from an opened `SecureDirectoryStream`, use
`NOFOLLOW_LINKS`, require a regular file, enforce a byte cap while reading, and
strictly decode UTF-8. Filesystems without secure directory traversal fail closed.

The application-facing host seam is deliberately small:

```text
runtime/create, runtime/describe, runtime/catalog
context/create, context/describe, context/evaluate
operation/invoke
events/subscribe, events/snapshot
value/describe
```

A fixed `--bb4t-internal` command runs evidence operations in the standalone jar and
native image. `bb4t.*` host namespaces are not projected into ordinary Babashka SCI
or bounded contexts.

Build provenance is generated into `META-INF/babashka/bb4t-commit` from the exact
fetched source commit before the standalone jar and native executable are built.
`runtime/create` does not accept a caller-supplied commit. Evaluation and direct
operation invocation return bounded `ValueDescription` data: small canonical values
may include inert data, large canonical values are summarized, and unsupported host
objects expose only an opaque type name.

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
runtime  sha256:f52a3a6de14a47189df11eb8a4bfbefd9d10a21ac05be8cce2d9f0e920d31c1e
catalog  sha256:41fb72e52a1082c26693349d610b5b8f8ae55e8441426662330d49c24ea9266b
vector   sha256:2c1e6b7c6f844c15a7f6a67b0828b0fdc6d38c4fe6d436275bc802471c776d48
```

The canonical domain accepts only nil, booleans, strings, characters, keywords,
symbols, mathematical integers, vectors, lists, sets, and maps. It rejects metadata,
floating-point/ratio ambiguity, records, Vars, functions, Classes, atoms, lazy
sequences, Paths, and other host objects.

Map/set insertion order does not change coordinates. A changed effective grant or
base authority policy does. Context coordinates exclude live SCI state, event data,
timestamps, and instance identity. An effective project-read coordinate intentionally
includes the resolved authorized root because changing that root changes authority.

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
15 tests, 112 assertions, 0 failures, 0 errors
```

Full native upstream plus BB1 main phase:

```text
362 tests, 1092 assertions, 0 failures, 0 errors
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
BB1 binary             88,148,224 bytes
delta                     851,968 bytes (+0.976%)
BB1 SHA-256             61a10a558384c35e54a2b6bf3228d487ca5e02c9c7ea2fa0e6539ef181a841b4

BB0 build wall             72.140 s
BB1 build wall             82.540 s
delta                      10.400 s (+14.42%)
```

The BB0 timing was measured on the host while BB1 used the pinned container, so the
build-time delta is directional rather than a controlled performance claim.

Native context construction after five warmups, 30 samples per profile:

```text
profile                 median       p95
agent/minimal           0.144 ms    0.219 ms
transform/pure          0.168 ms    0.213 ms
agent/project-read      0.212 ms    0.274 ms
```

The catalog has 3 capabilities and 3 operations. Capability projections contain
0/0, 1/2, and 2/3 namespace/Var counts for minimal, pure, and project-read. Including
the base `user` namespace with `apropos` and `doc`, total projected surface counts
are 1/2, 2/4, and 3/5. No Java class is projected.

From `bb4t/dev`, the measured BB1 coordinate changes 24 files with 2,494 insertions
and 9 deletions across eleven commits. From the immutable BB0 tag, product-roadmap
and BB1 work together change 29 files with 2,692 insertions and 82 deletions across
twelve commits.

Machine-readable values are in `artifacts/bb1-measurements.edn` and
`artifacts/bb1-authority.edn`. Raw logs, corpora, measurements, and the binary remain
under ignored `.bb1-ci-artifacts/` locally.

`PACKAGE_MANIFEST.json` remains the immutable bootstrap delivery manifest described
by the BB0 provenance record. Its checksums intentionally describe the delivered
starter package, not the current BB1 working tree.

## Security And Isolation Limits

BB1 demonstrates semantic capability restriction within the runtime. It does not
prove hostile-code filesystem/process isolation.

Specifically, BB1 does not establish:

- an operating-system, process, container, or VM sandbox;
- resistance to all SCI implementation defects;
- isolation from host resources reachable through a future trusted implementation;
- hostile concurrent filesystem mutation beyond secure relative traversal;
- network, CPU, memory, deadline, or process isolation;
- authorization policy correctness beyond the trusted ContextSpec decision;
- durable event integrity, confidentiality, or session replay.

Hard isolation remains a later worker/SmolVM boundary. SCI is not described as a
complete security sandbox.

## Required Questions

1. **Public APIs only?** Yes. BB1 uses the public SCI APIs listed above.
2. **Internals required?** No. Internal source was read, not called.
3. **Fail closed?** Yes for tested catalog/spec/profile/resource/surface inputs;
   unknown or widening inputs fail before context construction.
4. **Canonical manifests free of live objects?** Yes, enforced by rejection tests.
5. **Deterministic coordinates?** Yes for the defined canonical domain and vectors.
6. **Native matches JVM?** Yes for the normalized 51-cell corpus and 32 checks.
7. **Fork divergence?** 24 files, +2,494/-9 from `bb4t/dev` at the evidence commit.
8. **Claims not established?** OS/hostile-code isolation and the limits above.
9. **Clean external-client seam?** Yes, subject to fresh review; façades return
   bounded descriptions/data and do not expose mutable evaluator objects.
10. **Proceed, revise, or stop?** Proceed to the fresh BB1 review gate. Do not start
    bbagent until that review accepts or repairs this kernel.
