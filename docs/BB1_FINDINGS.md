# BB1 Findings

**Implementation coordinate:** `734c7c9d0f205f3ecd50600f588eb93374b31671`
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
denied. They also agreed on 19 packaged assurance checks covering malformed specs,
authority widening, direct dispatch, canonical data, bounded events, JSON bounds,
and project-root containment.

## Implementation

The canonical side is inert data:

- `RuntimeManifest` records upstream/SCI/build source coordinates, compiled
  capabilities, and the complete base SCI authority policy;
- `CapabilityCatalog` contains three `CapabilitySpec` values and three
  `SemanticOperation` descriptions;
- trusted `ContextSpec` data resolves against a fixed profile maximum;
- canonical tagged EDN trees produce domain-separated SHA-256 coordinates.

The live side is separate:

- `RuntimeState` owns implementation functions, real resource paths, event atoms,
  and subscribers;
- `ContextState` owns the SCI context, evaluation lock, and opaque instance identity;
- bounded SCI Vars capture a context and call the same checked semantic dispatcher
  as trusted host clients;
- no live object is accepted by canonicalization or returned by describe APIs.

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
runtime  sha256:0145fe19a83cff57f67b5c1883454b217f824e525bd925db2bbc1a666e1f34c1
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

The BB1 build cloned the exact published implementation commit recursively and
verified SCI `64163c4560e085ffdcf47951b406f62f753b7f4c`. The standalone JVM jar and
native executable emitted equal normalized parity payloads.

Focused JVM tests:

```text
15 tests, 82 assertions, 0 failures, 0 errors
```

Full native upstream plus BB1 main phase:

```text
362 tests, 1062 assertions, 0 failures, 0 errors
```

The remaining native phases also passed: flaky 15/23, preloads 1/1, preload
location 1/1, classpath environment 1/1, pod 1/2, and socket REPL 3/18.

An exploratory full upstream JVM run was not promoted to a BB1 gate. It reported
362 tests, 1045 assertions, zero failures, and two errors in pod-backed uberjar
metadata subprocesses under the scrubbed JVM environment. The BB1-focused JVM suite
and packaged JVM corpus passed; the same pod paths passed in the authoritative native
suite. The exact reason for the JVM-only subprocess EOFs was not established.

## Measurements

Against the recorded BB0 baseline:

```text
BB0 binary             87,296,256 bytes
BB1 binary             88,148,224 bytes
delta                     851,968 bytes (+0.976%)
BB1 SHA-256             f37741f4898b6d340453c3c0df851f327ef26245ed4ad6398d9d6423aa3e0a54

BB0 build wall             72.140 s
BB1 build wall             89.860 s
delta                      17.720 s (+24.56%)
```

The BB0 timing was measured on the host while BB1 used the pinned container, so the
build-time delta is directional rather than a controlled performance claim.

Native context construction after five warmups, 30 samples per profile:

```text
profile                 median       p95
agent/minimal           0.141 ms    0.145 ms
transform/pure          0.165 ms    0.181 ms
agent/project-read      0.218 ms    0.406 ms
```

The catalog has 3 capabilities and 3 operations. Projected SCI surfaces contain
0/0, 1/2, and 2/3 namespace/Var counts for minimal, pure, and project-read. No Java
class is projected.

From `bb4t/dev`, BB1 changes 19 files with 1,902 insertions and 3 deletions across
two implementation commits. From the immutable BB0 tag, product-roadmap and BB1
work together change 24 files with 2,100 insertions and 76 deletions.

Machine-readable values are in `artifacts/bb1-measurements.edn` and
`artifacts/bb1-authority.edn`. Raw logs, corpora, measurements, and the binary remain
under ignored `.bb1-ci-artifacts/` locally.

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
6. **Native matches JVM?** Yes for the normalized 51-cell corpus and 19 checks.
7. **Fork divergence?** 19 files, +1,902/-3 from `bb4t/dev` at the evidence commit.
8. **Claims not established?** OS/hostile-code isolation and the limits above.
9. **Clean external-client seam?** Yes, subject to fresh review; façades return data
   and do not require clients to access mutable implementation state.
10. **Proceed, revise, or stop?** Proceed to the fresh BB1 review gate. Do not start
    bbagent until that review accepts or repairs this kernel.
