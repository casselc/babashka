(ns bb4t.value
  (:require [bb4t.canonical :as canonical]))

(defn describe
  "Describes a value without projecting live host implementation objects."
  [value]
  (try
    (let [encoded (canonical/canonical-string value)]
      (if (<= (count encoded) 4096)
        {:value/kind :inert-data
         :value/data value}
        {:value/kind :inert-data
         :value/type (some-> value class .getName)
         :value/encoded-characters (count encoded)}))
    (catch clojure.lang.ExceptionInfo _
      {:value/kind :opaque
       :value/type (some-> value class .getName)})))
