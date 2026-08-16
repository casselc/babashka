(ns bb4t.value
  (:require [bb4t.canonical :as canonical]))

(def ^:private max-description-depth 64)
(def ^:private max-description-nodes 10000)

(defn- children [value]
  (cond
    (record? value) nil
    (map? value) (mapcat identity value)
    (or (vector? value) (list? value) (set? value)) value
    :else nil))

(defn- within-description-bounds? [root]
  (loop [pending (list [root 0])
         nodes 0]
    (if-let [[[value depth] & more] (seq pending)]
      (if (or (> depth max-description-depth)
              (>= nodes max-description-nodes))
        false
        (recur (reduce #(conj %1 [%2 (inc depth)])
                       more
                       (children value))
               (inc nodes)))
      true)))

(defn describe
  "Describes a value without projecting live host implementation objects."
  [value]
  (if-not (within-description-bounds? value)
    {:value/kind :opaque
     :value/type (some-> value class .getName)}
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
         :value/type (some-> value class .getName)}))))
