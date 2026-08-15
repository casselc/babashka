# BB0 Findings

**Date:** 2026-08-14
**Result:** Pass

## Demonstrated

This machine built an unmodified, stock-feature Babashka `v1.13.219` native
executable from the canonical upstream tag. The build used the tag's pinned
submodules and only upstream `script/uberjar` and `script/compile` for the native
artifact.

The selected source coordinate is:

```text
repository  https://github.com/babashka/babashka
tag         v1.13.219
commit      140ef9dcd770a54457a02fa29c3a2f643f4968d4
tree        ac30a258a272273d77a3ceb546281e3e18aca673
```

The canonical tag was checked with:

```bash
git ls-remote https://github.com/babashka/babashka.git refs/tags/v1.13.219
```

The working bb4t lineage is rooted directly at the selected release commit. The
bootstrap scaffold is applied to that source, and BB0 adds only build tooling and
evidence. The BB0 script also clones the selected tag into a fresh detached source
directory and verifies its commit before building.

The bootstrap input archive `bb4t-bootstrap-starter.zip` has SHA-256
`7515684cdeae680dbcd62c063a3d1821fba05e84606221865a048c1359b001dd`.
`PACKAGE_MANIFEST.json` contains the same JSON data as the copy embedded in that
archive; the working file differs only by a terminal newline. The embedded
manifest has SHA-256
`465c23edef51699e9eb764fd27f4d66d5cda1a138b22c18129c296d5123ca444`.
Its listed checksums describe the starter files as delivered, not the BB0 working
tree: `bb4t.edn` and `docs/UPSTREAM.md` were intentionally updated during BB0.

## Toolchain

```text
OS             Ubuntu 26.04 LTS
kernel         Linux 7.0.0-29-generic
architecture   x86_64
CPU            AMD RYZEN AI MAX+ 395 w/ Radeon 8060S, 32 logical CPUs
memory         131738619904 bytes
Java/GraalVM   Oracle GraalVM 25.0.4+7.1
native-image   25.0.4, Substrate VM serial GC, compressed references
Leiningen      2.9.8
GCC            15.2.0
glibc          2.43
```

The Oracle GraalVM archive used was
`graalvm-jdk-25.0.4_linux-x64_bin.tar.gz`, SHA-256
`76007c309f821aaf435bce63162ea0395587fc77350801c81643fe7feea37276`.
The release build documentation and CI selected Oracle GraalVM major version 25
and Leiningen 2.9.8; the exact GraalVM patch is a BB0 coordinate because upstream
did not pin it at this tag.

## Build Procedure

The exact tool setup used was:

```bash
mkdir /tmp/opencode/bb0-tools
BABASHKA_PLATFORM=linux GRAALVM_VERSION=25.0.4 \
  script/install-graalvm /tmp/opencode/bb0-tools

mkdir /tmp/opencode/bb0-tools/bin /tmp/opencode/bb0-tools/lein-home
curl --fail --location \
  --output /tmp/opencode/bb0-tools/bin/lein \
  https://raw.githubusercontent.com/technomancy/leiningen/2.9.8/bin/lein
chmod +x /tmp/opencode/bb0-tools/bin/lein
LEIN_HOME=/tmp/opencode/bb0-tools/lein-home \
  /tmp/opencode/bb0-tools/bin/lein self-install
```

The repeatable build command was:

```bash
PATH="/tmp/opencode/bb0-tools/bin:$PATH" \
LEIN_HOME=/tmp/opencode/bb0-tools/lein-home \
GRAALVM_HOME=/tmp/opencode/bb0-tools/graalvm-25.0.4 \
BB0_WORK_ROOT=/tmp/opencode/bb0-build \
script/bb0-build
```

`script/bb0-build` uses a fresh recursive clone, checks the exact commit, clears
the ambient environment, isolates Leiningen/Maven user state, sets the upstream
documented 6500 MiB image-builder heap, runs `script/uberjar` followed by
`script/compile`, and copies the output to `artifacts/bb0/bb`. The binary and
verbose logs are intentionally ignored by Git; the machine-readable measurement
record is `artifacts/bb0-baseline.edn`. The effective build `PATH` was
`/tmp/opencode/bb0-tools/graalvm-25.0.4/bin:/tmp/opencode/bb0-tools/bin:/usr/local/bin:/usr/bin:/bin`.

## Measurements

```text
measured build steps   script/uberjar && script/compile
build wall time        72140 ms
build peak RSS         4829384704 bytes
binary size            87296256 bytes
binary SHA-256         8acf3011fdce4878103e008a88eaf022b638eea2ab3c7001784f22bdaa23418b
startup command        artifacts/bb0/bb -e nil
startup samples        30, warm page cache
startup min            8.173 ms
startup median         9.908 ms
startup p95            10.639 ms
startup max            14.416 ms
```

Build timing excludes the source clone and tool installation. It includes Maven
dependency resolution in a fresh isolated user home. The startup timing uses
Bash `EPOCHREALTIME` around each process invocation and therefore includes a
small amount of shell timestamp overhead. The p95 uses the nearest-rank method;
later comparisons should use the same estimator.

The result is a stripped, dynamically linked x86-64 PIE requiring `libz.so.1`
and `libc.so.6`. `bb describe` reports version `1.13.219`, Git SHA
`140ef9dcd770a54457a02fa29c3a2f643f4968d4`, and the expected stock feature
set.

## Test Evidence

Direct and relocated clean-environment smoke tests passed:

```bash
artifacts/bb0/bb version
artifacts/bb0/bb describe
artifacts/bb0/bb -e '(assert (= 3 (+ 1 2))) (println :ok)'

tmp=$(mktemp -d /tmp/opencode/bb0-smoke.XXXXXX)
mkdir "$tmp/home"
cp artifacts/bb0/bb "$tmp/bb"
env -i HOME="$tmp/home" PATH=/usr/bin:/bin "$tmp/bb" version
env -i HOME="$tmp/home" PATH=/usr/bin:/bin "$tmp/bb" describe
env -i HOME="$tmp/home" PATH=/usr/bin:/bin \
  "$tmp/bb" -e '(assert (= 3 (+ 1 2))) (println :ok)'
```

The upstream native harness was run from the detached release clone:

```bash
SOURCE_DIR=$(<artifacts/bb0/source-path.txt)
(
  cd "$SOURCE_DIR"
  env -i \
    HOME="$SOURCE_DIR/.bb0-home" \
    LANG=C.UTF-8 \
    PATH="/tmp/opencode/bb0-tools/graalvm-25.0.4/bin:/tmp/opencode/bb0-tools/bin:/usr/local/bin:/usr/bin:/bin" \
    JAVA_HOME=/tmp/opencode/bb0-tools/graalvm-25.0.4 \
    GRAALVM_HOME=/tmp/opencode/bb0-tools/graalvm-25.0.4 \
    LEIN_HOME=/tmp/opencode/bb0-tools/lein-home \
    LEIN_JVM_OPTS="-Duser.home=$SOURCE_DIR/.bb0-home" \
    BABASHKA_TEST_ENV=native \
    script/test
)
```

Reported results were:

```text
main suite             347 tests, 980 assertions, 0 failures, 0 errors
flaky selector          15 tests,  23 assertions, 0 failures, 0 errors
preloads                 1 test,    1 assertion,  0 failures, 0 errors
preload location         1 test,    1 assertion,  0 failures, 0 errors
classpath environment    1 test,    1 assertion,  0 failures, 0 errors
pod                      1 test,    2 assertions, 0 failures, 0 errors
socket REPL              3 tests,  18 assertions, 0 failures, 0 errors
```

Upstream's `script/test` deliberately masks the exit status of its flaky-selector
phase, but that phase itself reported zero failures and zero errors in this run.
The pod test emitted its expected exercised error message and still reported a
passing result. The broader, network-sensitive `script/run_lib_tests` compatibility
suite was not run.

## Deviations

There were no source, dependency, feature, SCI-surface, or runtime deviations in
the produced executable. Mechanical BB0 additions are limited to the detached
build wrapper, provenance/measurement files, documentation, and ignoring the
large local binary/log directory.

The local build used the release's documented dynamic Linux path. The tag's
workflow had Linux native matrix entries commented out, so this result does not
claim parity with an upstream Linux CI artifact or with Babashka's static/musl
release packaging.

## Lineage Repair

The original branch topology accidentally placed bootstrap commit
`f6f589b5f6ea03e3099fdc4bbdfc444763c3a966` and BB0 head
`9dfa19a4eda78aafd3fb5d4da0b09deec1affba0` after later upstream commit
`397017529b832f0584c655bbbe34058452ca9426` (`1.13.220-SNAPSHOT`). That
introduced unrelated upstream changes into the bb4t lineage even though the BB0
measurement itself correctly used `v1.13.219`.

The existing commits were replayed onto the measured source coordinate:

```text
v1.13.219 / 140ef9dcd770a54457a02fa29c3a2f643f4968d4
  -> bootstrap 3eb3bf5990bb98c2d55c4f9d3c22d5b4d7603c03
  -> BB0 evidence e9ea529132ae511e8b8995e80272faa279c0d332
  -> BB0 review 58aea0834ad9dfabf41eff516a62829dc3146c22
```

The last SHA above is the repaired BB0 evidence head before this
documentation-only lineage record. The live `bb4t/bb0` tip is obtained with
`git rev-parse bb4t/bb0`; a commit cannot contain its own final SHA. Both the
bootstrap and BB0 trees retain SCI submodule
`64163c4560e085ffdcf47951b406f62f753b7f4c`.

Verification included:

```bash
git merge-base 140ef9dcd770a54457a02fa29c3a2f643f4968d4 bb4t/bootstrap
git merge-base bb4t/bootstrap bb4t/bb0
git log --graph --oneline --decorate 140ef9dc^..bb4t/bb0
git diff --stat 140ef9dcd770a54457a02fa29c3a2f643f4968d4...bb4t/bootstrap
git diff --stat bb4t/bootstrap...bb4t/bb0
git diff --submodule 140ef9dcd770a54457a02fa29c3a2f643f4968d4...bb4t/bootstrap
git diff --submodule bb4t/bootstrap...bb4t/bb0
docker build --no-cache --file Dockerfile.bb0 --target ci \
  --tag bb4t-bb0-ci-lineage .
```

The merge bases resolved to the expected release and bootstrap commits. The first
diff contained only the 15 bootstrap-scaffold files; the second contained only
the 9 BB0 build/evidence files; both submodule diffs were empty. The uncached
Docker build cloned commit `140ef9d...`, tree `ac30a258...`, and SCI
`64163c45...`; all seven native test phases and the version, describe, and
evaluation smoke checks passed.

The rebuilt container binary was 87165184 bytes with SHA-256
`ccc208548d17fab97b5ccfc8ab8127b8c84a727029cdbc94b4ddb3b5a4680237`,
which differs from the historical host measurement. It reports the same version,
Git SHA, features, and dynamic library surface. The historical measurement remains
unchanged because BB0 does not claim bit-for-bit reproducibility. The lineage
repair therefore does not change the original BB0 conclusions.

## Reproducibility Gaps

- The Oracle download URL is versioned and its observed digest is recorded, but
  the installer does not verify that digest before extraction.
- Maven/Clojars artifacts are selected by pinned dependency versions but are not
  locked by content digest. A fresh build still depends on those repositories.
- Only one successful native artifact was measured. Bit-for-bit reproducibility
  across repeated builds or machines was not demonstrated.
- The dynamic artifact depends on the host glibc and zlib ABI.
- `script/run_lib_tests` was not run, so evidence covers the native upstream test
  harness and smoke behavior rather than the full external-library matrix.
- The ignored binary and detailed logs are local machine artifacts; the tracked
  EDN record contains their coordinates, not the binary itself.

## Recommendation

BB1 should proceed after BB0 review. The required baseline build, binary,
provenance, measurements, and strong native test evidence exist, and no runtime
divergence was needed. BB1 comparisons should use `artifacts/bb0-baseline.edn`
and should not interpret this BB0 result as evidence for static packaging,
bit-reproducible output, or authority isolation.
