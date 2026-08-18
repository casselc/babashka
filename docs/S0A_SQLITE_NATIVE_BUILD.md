# S0a SQLite Native Application Build

## Result

**Pass for the bbagent-specific Linux x86_64 build seam.** bb4t commit
`f438307280b7a01fd20e99f54cd82682ef15d12a` adds `next.jdbc` `1.3.1118` and
sqlite-jdbc `3.53.2.1` only to `:app/bbagent` and preserves multi-release JAR
semantics so xerial's GraalVM hosted feature is visible.

Ordinary bb4t dependency resolution excludes both additions. The old broad
`:feature/jdbc` and `:feature/sqlite` profiles remain unchanged and inactive. No
runtime, context, catalog, kernel, operation, or SCI projection source changed from
the immutable `bb4t-a0` application hook.

## Native Evidence

The source-pinned bbagent wrapper `f979f96449ccb648a737832125a93b5616399014`
built bbagent implementation `45a63073ad02646bb99b2eb9d182060c7473b432`
with:

```text
Oracle GraalVM       25.2.4+7.1
Java/native-image    25.0.4
Leiningen            2.11.2
target               Linux x86_64 glibc, -march=compatibility
native-image phase   40.4 s
```

The assembled uberjar retained xerial and next.jdbc native-image properties,
`SqliteJdbcFeature`, JDBC service metadata, and the target native resource. Native
analysis listed `SqliteJdbcFeature` and registered `75` types, `79` fields, and `72`
methods for JNI access.
Build-time export produced:

```text
bbagent bytes        64,293,120
bbagent SHA-256      d475952421709e95d64282937f7fe77ba0ed03a0ddf371906d35f24bfaf58f96
sidecar bytes         1,093,888
sidecar SHA-256      f374da845a36d0a663521457f8e454413325e3b8247a15c2677426f4b15cf6ac
```

The wrapper hash-binds the exported sidecar to the pinned xerial resource, relocates
the bundle, proves no runtime extraction in a dedicated temp directory, and requires
failure when the sidecar is absent. The exact checkout also passes the accepted BB1
focused JVM suite: 18 tests and 164 assertions. Log and artifact hashes are retained
in bbagent `artifacts/s0a-evidence.edn`.

## Boundary

This is application build evidence, not a change to BB1 RuntimeManifest semantics.
Physical app/dependency/native reachability is recorded separately pending later BB2
BuildManifest work. It grants no SQLite/JDBC model authority and does not authorize a
journal migration. The primary report is bbagent
`docs/S0A_SQLITE_NATIVE_FINDINGS.md`.
