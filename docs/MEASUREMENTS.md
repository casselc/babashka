# BB0 Baseline Measurements

The purpose is a regression baseline, not a publication-quality benchmark.

Write the observed result to:

```text
artifacts/bb0-baseline.edn
```

Do not commit large build products merely because this file names them.

## Required coordinates

```clojure
{:schema/version 1

 :upstream
 {:repository ...
  :tag ...
  :commit ...}

 :platform
 {:os ...
  :arch ...
  :cpu ...
  :memory-bytes ...optional...}

 :toolchain
 {:java ...
  :graalvm ...
  :native-image ...}

 :build
 {:command [...]
  :wall-ms ...
  :peak-rss-bytes ...optional...
  :result :pass|:fail}

 :binary
 {:path ...
  :bytes ...
  :sha256 ...}

 :startup
 {:command [...]
  :samples 30
  :median-ms ...
  :p95-ms ...
  :min-ms ...
  :max-ms ...}

 :runtime
 {:reported-version ...
  :smoke-tests [...]
  :notes [...]}}
```

Use `nil`/absence plus an explanatory note for measurements that cannot be collected
reliably; do not invent zeros.

## Startup measurement

Use a trivial command that exits immediately and is stable across runs.

Record:

- exact command;
- number of samples;
- whether the filesystem/page cache is likely warm;
- median and p95;
- min/max as diagnostics.

Do not overinterpret sub-millisecond differences.

## Build measurement

At minimum record wall-clock time.

Peak RSS is useful if a reliable local mechanism is available. Do not add a large
benchmark dependency merely to collect it.

## Binary measurement

Record:

```text
size in bytes
SHA-256 digest
```

If stripping/compression/signing modifies the artifact later, record those as separate
coordinates rather than silently replacing the baseline digest.

## Future BB1 additions

BB1 may add:

```text
context-construction latency
per-context retained memory
namespace/Var/class counts
capability count
negative authority test count
native-vs-JVM behavior parity
```

Do not add these to BB0 before the underlying abstractions exist.
