(ns bb4t.operation
  (:require [bb4t.kernel :as kernel]))

(defn invoke
  "Invokes a granted SemanticOperation and returns an inert value description."
  [context operation-id args]
  (kernel/invoke context operation-id args))
