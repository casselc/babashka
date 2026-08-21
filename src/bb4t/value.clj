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

(def ^:private max-realized-elements 1000)

(defn- lazy-prefix
  "A bounded realization of an unrealized sequence, or nil.

  take, map, filter, drop and distinct all return lazy sequences, which are
  neither vectors nor lists and so fell outside the canonical data domain
  entirely -- they described as opaque. Expanding the bounded vocabulary made
  that the common case rather than a corner: the caller could finally compose,
  and then could not see what it had composed. Realization is bounded, so an
  unbounded sequence truncates rather than hanging."
  [value]
  (when (and (seq? value) (not (counted? value)))
    (let [prefix (into [] (take (inc max-realized-elements)) value)]
      (if (> (count prefix) max-realized-elements)
        {:elements (subvec prefix 0 max-realized-elements) :more? true}
        {:elements prefix :more? false}))))

(defn realize
  "Forces an unrealized sequence to a bounded prefix, for the caller to run
   inside the evaluation it came from.

  Realization runs the sequence's own computation, so it must happen while the
  evaluation is still in progress: inside the out/err capture, and before the
  success event is emitted. Doing it at description time would print into
  nothing and could fail after the evaluation had already been recorded as
  succeeding.

  Returns {:value v :origin nil} for anything already realized."
  [value]
  (if-let [lazy (lazy-prefix value)]
    {:value (:elements lazy)
     :origin (cond-> {:value/type (some-> value class .getName)}
               (:more? lazy) (assoc :value/truncated? true
                                    :value/elements max-realized-elements))}
    {:value value :origin nil}))

(defn describe
  "Describes a value without projecting live host implementation objects.

  Pass origin from `realize` when the value was forced, so the description
  reports the sequence it came from rather than the vector it became."
  ([value] (describe value nil))
  ([value origin]
   (let [target value]
    (if-not (within-description-bounds? target)
      {:value/kind :opaque
       :value/type (some-> value class .getName)}
      (try
        (let [encoded (canonical/canonical-string target)]
          (merge
           ;; origin last: it names the sequence the value came from, which
           ;; the realized vector's own class would otherwise overwrite.
           (if (<= (count encoded) max-encoded-characters)
             {:value/kind :inert-data
              :value/data target}
             (cond-> {:value/kind :inert-data
                      :value/type (some-> value class .getName)
                      :value/encoded-characters (count encoded)
                      :value/truncated? true
                      :value/preview (preview target encoded)}
               (string? target) (assoc :value/characters (count target))))
           origin))
        (catch clojure.lang.ExceptionInfo _
          {:value/kind :opaque
           :value/type (some-> value class .getName)}))))))
