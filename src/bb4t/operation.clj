(ns bb4t.operation
  (:require [bb4t.kernel :as kernel]))

(defn invoke
  "Invokes a SemanticOperation after rechecking the Context's effective grant."
  [context operation-id args]
  (kernel/invoke context operation-id args))
