(ns bb4t.transcript
  "Operation transcripts: what a bounded evaluation did at the world boundary.

  A Context's computational state is rebuilt by running its source again.
  That is exact for ordinary Clojure and wrong for a semantic operation, which
  either observes a world that has since changed or changes one that has
  already been changed.  A transcript separates the two: the source runs, and
  the operations inside it are recorded once and reproduced thereafter."
  (:require [bb4t.kernel :as kernel]))

(defn recorder
  "A transcript that records the semantic operations an evaluation invokes.

  The caller owns it, so the receipts are readable whether the evaluation
  returned or threw -- a form that changed the project and then failed has
  still changed it, and its receipt is the only record that it did."
  []
  (kernel/create-recorder))

(defn player
  "A transcript that reproduces recorded operations instead of invoking them."
  [receipts]
  (kernel/create-player receipts))

(defn legacy
  "A transcript for source recorded before transcripts existed.

  Observations run against the live world and are counted; an actuation fails
  closed, because there is no receipt saying the change was already made."
  []
  (kernel/create-legacy))

(defn operations
  "The receipts a recorder captured, in invocation order."
  [transcript]
  (kernel/transcript-operations transcript))

(defn observations
  "The operation IDs a legacy transcript re-observed, in invocation order."
  [transcript]
  (kernel/transcript-observations transcript))
