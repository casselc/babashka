# Upstream and Build Provenance

## Intended baseline

The bootstrap branch was created from the selected upstream Babashka release:

```text
upstream repository: https://github.com/babashka/babashka
selected tag:        v1.13.219
selected commit:     140ef9dcd770a54457a02fa29c3a2f643f4968d4
selected tree:       ac30a258a272273d77a3ceb546281e3e18aca673
```

BB0 verified the tag against the canonical upstream repository on 2026-08-14.
The working `bb4t/bootstrap` lineage is rooted directly at that commit, and
`script/bb0-build` independently verifies it in a fresh detached clone.

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
