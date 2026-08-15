# BB0 Local CI

`Dockerfile.bb0` provides a local Linux/amd64 CI path for the pinned BB0
baseline. It does not use host `/tmp`: persistent source, toolchain, build,
test, and smoke-test work is kept under `/workspace` in Docker-managed storage.
Docker/Podman may use its own configured storage internally; this workflow does
not create source, toolchain, build, or artifact directories in host `/tmp`.

GraalVM's native-image driver and several unmodified upstream tests use
container-local `/tmp` paths. Those accesses remain inside the disposable
container; retained CI work and exported artifacts do not use host `/tmp`.

The CI target:

1. installs the recorded Oracle GraalVM 25.0.4 archive after verifying its BB0
   SHA-256 digest;
2. installs Leiningen 2.9.8 after verifying the installer script's SHA-256
   digest;
3. runs `script/bb0-build` with `BB0_WORK_ROOT=/workspace/.bb0-work`;
4. runs the upstream native `script/test` harness;
5. runs version, describe, and evaluation smoke checks from a relocated binary
   under a clean environment.

Run local CI:

```bash
docker build --file Dockerfile.bb0 --target ci --tag bb4t-bb0-ci .
```

Export the binary and detailed build/test logs into the repository instead of
`/tmp`:

```bash
docker build \
  --file Dockerfile.bb0 \
  --target artifacts \
  --output type=local,dest=.bb0-ci-artifacts \
  .
```

The exported directory is ignored by Git. The container pins the Ubuntu 26.04
base image to digest
`sha256:678c6550cc43645e08669028bc177f50be4e7c5b8cca677067b1914d4afc7a03`.
Ubuntu packages installed from the distribution repositories and dependency
repository content remain time-variable, so the binary digest may differ from
`artifacts/bb0-baseline.edn`. This target is behavioral local CI, not a claim of
bit-for-bit artifact reproducibility and not a replacement for the recorded BB0
measurement. The export omits `source-path.txt` because it points into the
disposable build container.

The target is intentionally Linux/amd64-only because BB0 measured that platform
and records the x64 GraalVM archive digest. SmolVM packaging remains outside BB0
scope.
