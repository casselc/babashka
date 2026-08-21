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

(def ^:private max-encoded-characters 4096)

(def ^:private max-preview-characters 2048)

(defn- preview
  "A bounded prefix of an oversized value.

  Reporting only a size taught the caller that something large existed and
  nothing about what it was, which for a file read is the whole content. A
  string previews as its own text, since that is what the caller asked for;
  anything else previews as its canonical encoding."
  [value encoded]
  (let [source (if (string? value) value encoded)]
    (subs source 0 (min max-preview-characters (count source)))))

(defn describe
  "Describes a value without projecting live host implementation objects."
  [value]
  (if-not (within-description-bounds? value)
    {:value/kind :opaque
     :value/type (some-> value class .getName)}
    (try
      (let [encoded (canonical/canonical-string value)]
        (if (<= (count encoded) max-encoded-characters)
          {:value/kind :inert-data
           :value/data value}
          (cond-> {:value/kind :inert-data
                   :value/type (some-> value class .getName)
                   :value/encoded-characters (count encoded)
                   :value/truncated? true
                   :value/preview (preview value encoded)}
            (string? value) (assoc :value/characters (count value)))))
      (catch clojure.lang.ExceptionInfo _
        {:value/kind :opaque
         :value/type (some-> value class .getName)}))))
