(ns bb4t.canonical
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(defn- reject! [value reason]
  (throw (ex-info "Value is outside the canonical data domain"
                  {:canonical/error reason
                   :value/type (some-> value class .getName)})))

(defn- without-meta! [value]
  (when (and (instance? clojure.lang.IMeta value)
             (seq (meta value)))
    (reject! value :metadata))
  value)

(declare canonical-tree)

(defn- canonical-pr-str [value]
  (binding [*print-length* nil
            *print-level* nil
            *print-readably* true
            *print-dup* false]
    (pr-str value)))

(defn- encoded [value]
  (canonical-pr-str (canonical-tree value)))

(defn canonical-tree
  "Converts a deliberately small inert-data domain to an ordered tagged tree."
  [value]
  (without-meta! value)
  (cond
    (nil? value) [:nil]
    (boolean? value) [:boolean value]
    (string? value) [:string value]
    (char? value) [:character (str value)]
    (keyword? value) [:keyword (namespace value) (name value)]
    (symbol? value) [:symbol (namespace value) (name value)]
    (integer? value) [:integer (str (bigint value))]
    (record? value) (reject! value :record)
    (map? value) [:map (->> value
                            (map (fn [[k v]]
                                   [(canonical-tree k) (canonical-tree v)]))
                             (sort-by (comp canonical-pr-str first))
                            vec)]
    (vector? value) [:vector (mapv canonical-tree value)]
    (list? value) [:list (mapv canonical-tree value)]
    (set? value) [:set (->> value
                            (sort-by encoded)
                            (mapv canonical-tree))]
    :else (reject! value :unsupported-type)))

(defn canonical-string [value]
  (canonical-pr-str (canonical-tree value)))

(defn sha-256 [^String value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes value StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn coordinate
  "Returns a domain-separated SHA-256 coordinate for inert semantic data."
  [kind value]
  (when-not (qualified-keyword? kind)
    (throw (ex-info "Coordinate kind must be a qualified keyword"
                    {:coordinate/kind kind})))
  (str "sha256:"
       (sha-256
        (canonical-pr-str
         [:bb4t.coordinate/v1 kind (canonical-tree value)]))))
