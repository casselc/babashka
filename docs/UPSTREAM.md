# Upstream and Build Provenance

## Intended baseline

The bootstrap branch was created from the selected upstream Babashka release:

```text
upstream repository: https://github.com/babashka/babashka
selected tag:        v1.13.219
selected commit:     FILL DURING BB0
```

Do not trust this document's tag-to-commit mapping until BB0 records the actual Git
object from the local repository.

## Record during BB0

Capture:

```clojure
{:upstream
 {:repository "https://github.com/babashka/babashka"
  :tag "v1.13.219"
  :commit "..."
  :tree "...optional..."}

 :platform
 {:os "..."
  :arch "..."}

 :jdk
 {:distribution "..."
  :version "..."}

 :graalvm
 {:distribution "..."
  :version "..."
  :native-image "..."}

 :build
 {:command [...]
  :environment-notes [...]}}
```

Prefer exact machine-readable versions and digests where practical.

## Upstream divergence policy

The fork should remain recognizable as Babashka plus a curated runtime overlay.

Every intentional divergence should eventually be attributable to one of:

```text
runtime/capability catalog
dynamic SCI context construction
measured feature removal
measured library replacement
selected embedded library
native-image support for selected capability
bb4t build/profile tooling
compatibility/authority tests
```

If a change cannot be connected to one of those reasons, question whether it belongs
in this fork.

## Updating upstream

Do not optimize this workflow during BB0.

Long term the desired shape is:

```text
new upstream tag
      ↓
merge/rebase bb4t delta
      ↓
native build
      ↓
compatibility suite
      ↓
authority suite
      ↓
measurement deltas
      ↓
publish pinned bb4t binary
```
