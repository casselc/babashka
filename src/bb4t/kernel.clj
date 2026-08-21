(ns bb4t.kernel
  (:require [bb4t.canonical :as canonical]
             [bb4t.catalog :as catalog]
             [bb4t.value :as value]
             [cheshire.core :as json]
             [clojure.java.io :as io]
             [clojure.string :as str]
            [sci.core :as sci])
  (:import [java.io ByteArrayOutputStream StringReader StringWriter]
           [java.nio ByteBuffer]
           [java.nio.charset CodingErrorAction StandardCharsets]
           [java.nio.channels SeekableByteChannel]
           [java.nio.file DirectoryStream Files LinkOption OpenOption Path Paths
            SecureDirectoryStream StandardOpenOption]
            [java.nio.file.attribute BasicFileAttributes BasicFileAttributeView
             FileAttribute]
            [java.time Instant]
            [java.util Collections Map UUID WeakHashMap]))

(set! *warn-on-reflection* true)

(def ^:private upstream-commit
  "140ef9dcd770a54457a02fa29c3a2f643f4968d4")

(def ^:private sci-commit
  "64163c4560e085ffdcf47951b406f62f753b7f4c")

(def ^:private build-commit-resource
  (some-> (io/resource "META-INF/babashka/bb4t-commit")
          slurp
          str/trim
          not-empty))

(defn- build-commit []
  (or build-commit-resource
      (throw (ex-info "BB1 build provenance resource is missing" {}))))

(def ^:private default-event-limit 256)
(def ^:private max-json-bytes 1048576)
(def ^:private max-json-depth 64)
(def ^:private max-json-nodes 10000)

(defrecord RuntimeState
  [manifest coordinate catalog catalog-coordinate implementations resources
   resource-descriptions event-limit event-state subscribers])

(defrecord ContextState
  [runtime instance-id spec spec-coordinate effective coordinate sci-context lock
   projections allow])

(defn- fail! [message data]
  (throw (ex-info message (assoc data :bb4t/error :validation))))

(def ^:private ^Map runtime-handles
  (Collections/synchronizedMap (WeakHashMap.)))

(def ^:private ^Map context-handles
  (Collections/synchronizedMap (WeakHashMap.)))

(defn- register-handle [^Map registry state]
  (let [handle (Object.)]
    (.put registry handle state)
    handle))

(defn- resolve-handle [^Map registry kind handle]
  (locking registry
    (or (some (fn [[registered state]]
                (when (identical? registered handle) state))
              registry)
        (fail! "Unknown or expired bb4t handle" {:handle/kind kind}))))

(defn- exact-keys! [kind value allowed]
  (when-not (map? value)
    (fail! (str (name kind) " must be a map") {kind value}))
  (let [unknown (seq (remove allowed (keys value)))]
    (when unknown
      (fail! (str (name kind) " contains unknown keys")
             {kind value :unknown/keys (set unknown)}))))

(defn- qualified-keyword-set! [kind value]
  (when-not (and (set? value) (every? qualified-keyword? value))
    (fail! (str (name kind) " must be a set of qualified keywords")
           {kind value})))

(defn validate-catalog
  "Validates one inert catalog as an atomic unit and returns it unchanged."
  [capability-catalog implementation-ids]
  (exact-keys! :catalog capability-catalog
               #{:catalog/version :catalog/type :capabilities})
  (when-not (= 1 (:catalog/version capability-catalog))
    (fail! "Unsupported catalog version"
           {:catalog/version (:catalog/version capability-catalog)}))
  (when-not (= :bb4t/capability-catalog (:catalog/type capability-catalog))
    (fail! "Unsupported catalog type"
           {:catalog/type (:catalog/type capability-catalog)}))
  (when-not (map? (:capabilities capability-catalog))
    (fail! "Catalog capabilities must be a map" {}))
  (let [entries (vals (:capabilities capability-catalog))
        projection-keys
        (map (juxt #(get-in % [:operation :sci/namespace])
                   #(get-in % [:operation :sci/var])) entries)
        operation-ids (map #(get-in % [:operation :operation/id]) entries)
        described-implementations (set (map :implementation/id entries))]
    (doseq [[capability-id capability] (:capabilities capability-catalog)]
      (exact-keys! :capability capability
                   #{:capability/id :effects :doc :implementation/id :operation})
      (when-not (= capability-id (:capability/id capability))
        (fail! "Capability map key does not match its ID"
               {:capability/key capability-id
                :capability/id (:capability/id capability)}))
      (when-not (qualified-keyword? capability-id)
        (fail! "Capability ID must be a qualified keyword"
               {:capability/id capability-id}))
      (when-not (and (set? (:effects capability))
                     (every? qualified-keyword? (:effects capability)))
        (fail! "Capability effects must be qualified keywords"
               {:capability/id capability-id}))
      (when-not (qualified-keyword? (:implementation/id capability))
        (fail! "Implementation ID must be a qualified keyword"
               {:capability/id capability-id}))
      (let [operation (:operation capability)
            sci-ns (:sci/namespace operation)]
        (exact-keys! :operation operation
                     #{:operation/id :input/schema :output/schema :sci/namespace
                       :sci/var :doc :arglists})
        (when-not (qualified-keyword? (:operation/id operation))
          (fail! "Operation ID must be a qualified keyword"
                 {:capability/id capability-id}))
        (when-not (and (simple-symbol? sci-ns)
                       (simple-symbol? (:sci/var operation)))
          (fail! "SCI projections require simple namespace and Var symbols"
                 {:capability/id capability-id}))
        (when (or (= 'user sci-ns)
                  (= 'clojure.core sci-ns)
                  (str/starts-with? (str sci-ns) "bb4t"))
          (fail! "SCI projection targets a reserved namespace"
                 {:capability/id capability-id :sci/namespace sci-ns}))))
    (when-not (= (count projection-keys) (count (distinct projection-keys)))
      (fail! "SCI projections must be unique" {}))
    (when-not (= (count operation-ids) (count (distinct operation-ids)))
      (fail! "Operation IDs must be unique" {}))
    (when-not (= described-implementations implementation-ids)
      (fail! "Catalog and implementation registry differ"
             {:catalog/implementation-ids described-implementations
              :registry/implementation-ids implementation-ids})))
  (canonical/coordinate :bb4t/catalog capability-catalog)
  capability-catalog)

(defn- bounded-json-data! [value]
  (let [nodes (volatile! 0)]
    (letfn [(walk [value depth]
              (vswap! nodes inc)
              (when (> @nodes max-json-nodes)
                (fail! "JSON value exceeds node limit"
                       {:limit max-json-nodes}))
              (when (> depth max-json-depth)
                (fail! "JSON value exceeds depth limit"
                       {:limit max-json-depth}))
              (cond
                (or (nil? value) (boolean? value) (string? value)) nil
                (integer? value) nil
                (number? value)
                (fail! "JSON numbers must be integers"
                       {:value/type (some-> value class .getName)})
                (vector? value) (doseq [item value] (walk item (inc depth)))
                (and (map? value) (not (record? value)))
                (doseq [[key item] value]
                  (when-not (string? key)
                    (fail! "JSON object keys must be strings" {:key key}))
                  (walk item (inc depth)))
                :else (fail! "Unsupported JSON value"
                             {:value/type (some-> value class .getName)})))]
      (walk value 0)
      value)))

(defn- utf8-bytes [^String value]
  (.getBytes value StandardCharsets/UTF_8))

(defn- utf8-byte-count [^String value]
  (let [^bytes bytes (utf8-bytes value)]
    (alength bytes)))

(defn- json-read [[input :as args]]
  (when-not (and (= 1 (count args)) (string? input))
    (fail! "data.json/read expects one string" {:operation/id :data.json/read}))
  (when (> (utf8-byte-count input) max-json-bytes)
    (fail! "JSON input exceeds byte limit" {:limit max-json-bytes}))
  (let [value (json/parse-string-strict input)
        value-count
        (with-open [reader (StringReader. input)]
          (count (doall (json/parsed-seq reader))))]
    (when-not (= 1 value-count)
      (fail! "JSON input must contain exactly one value"
             {:value/count value-count}))
    (bounded-json-data! value)))

(defn- json-write [[value :as args]]
  (when-not (= 1 (count args))
    (fail! "data.json/write expects one value" {:operation/id :data.json/write}))
  (bounded-json-data! value)
  (let [output (json/generate-string value)]
    (when (> (utf8-byte-count output) max-json-bytes)
      (fail! "JSON output exceeds byte limit" {:limit max-json-bytes}))
    output))

(defn- read-bounded-channel [^SeekableByteChannel channel max-bytes]
  (with-open [output (ByteArrayOutputStream.)]
    (let [^bytes buffer (byte-array 8192)]
      (loop [total 0]
        (let [remaining (- (inc max-bytes) total)
              byte-buffer (ByteBuffer/wrap buffer 0
                                           (min (alength buffer) remaining))
              read-count (.read channel byte-buffer)]
          (cond
            (neg? read-count)
            (.toByteArray output)

            (> (+ total read-count) max-bytes)
            (fail! "Project file exceeds byte limit"
                   {:limit max-bytes})

            :else
            (do
              (.write output buffer 0 read-count)
              (recur (+ total read-count)))))))))

(defn- close-directories! [directories]
  (doseq [^DirectoryStream directory (reverse directories)]
    (try
      (.close directory)
      (catch Throwable _))))

(defn- secure-project-bytes [^Path root ^Path relative-target max-bytes]
  (let [components (mapv #(.getName relative-target %)
                         (range (.getNameCount relative-target)))
        opened (atom [])]
    (when (empty? components)
      (fail! "project/read target is not a regular file" {}))
    (try
      (let [root-directory (Files/newDirectoryStream root)]
        (swap! opened conj root-directory)
        (when-not (instance? SecureDirectoryStream root-directory)
          (fail! "Filesystem cannot provide secure project traversal"
                 {:filesystem/feature :secure-directory-stream}))
        (loop [^SecureDirectoryStream directory root-directory
               [component & more] components]
          (if (seq more)
            (let [^SecureDirectoryStream child
                  (.newDirectoryStream
                   directory component
                   (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))]
              (swap! opened conj child)
              (recur child more))
            (let [^BasicFileAttributeView attributes-view
                  (.getFileAttributeView
                   directory component BasicFileAttributeView
                   (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                  ^BasicFileAttributes attributes
                  (.readAttributes attributes-view)]
              (when-not (.isRegularFile attributes)
                (fail! "project/read target is not a regular file" {}))
              (with-open [^SeekableByteChannel channel
                          (.newByteChannel
                           directory component
                           #{StandardOpenOption/READ LinkOption/NOFOLLOW_LINKS}
                           (make-array FileAttribute 0))]
                (read-bounded-channel channel max-bytes))))))
      (finally
        (close-directories! @opened)))))

(defn- entry-kind [^BasicFileAttributes attributes]
  (cond
    (.isRegularFile attributes) :file
    (.isDirectory attributes) :directory
    (.isSymbolicLink attributes) :symlink
    :else :other))

(defn- directory-entries
  "Lists one directory's immediate entries as inert data.

  Attributes are read through the open SecureDirectoryStream with
  NOFOLLOW_LINKS, so a symbolic link is reported as a link rather than
  followed to whatever it names, inside or outside the root.  Nothing here
  recurses: listing is one directory deep by construction, so no traversal
  can walk out of the authorized root."
  [^SecureDirectoryStream directory max-entries]
  (let [entries
        (loop [remaining (iterator-seq (.iterator directory))
               collected (transient [])
               seen 0]
          (if-let [^Path entry (first remaining)]
            (do
              (when (>= seen max-entries)
                (fail! "Project directory exceeds entry limit"
                       {:limit max-entries}))
              (let [^Path name-path (.getFileName entry)
                    ^BasicFileAttributeView view
                    (.getFileAttributeView
                     directory name-path BasicFileAttributeView
                     (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                    ^BasicFileAttributes attributes (.readAttributes view)
                    kind (entry-kind attributes)]
                (recur (rest remaining)
                       (conj! collected
                              (cond-> {:name (str name-path) :kind kind}
                                (= :file kind)
                                (assoc :bytes (.size attributes))))
                       (inc seen))))
            (persistent! collected)))]
    ;; Sorted so a listing is a value, not an artefact of iteration order.
    (vec (sort-by :name entries))))

(defn- secure-project-listing
  "Opens relative-target as a directory under root and lists it.

  Uses the same secure component-by-component descent as reading, so an
  intermediate symbolic link cannot redirect the walk."
  [^Path root ^Path relative-target max-entries]
  (let [;; Relativizing the root against itself yields the empty path, which
        ;; reports one nameless component rather than none. Listing the root
        ;; is this operation's primary use, so drop those instead of
        ;; descending into a component named "".
        components (into []
                         (comp (map #(.getName relative-target %))
                               (remove #(str/blank? (str %))))
                         (range (.getNameCount relative-target)))
        opened (atom [])]
    (try
      (let [root-directory (Files/newDirectoryStream root)]
        (swap! opened conj root-directory)
        (when-not (instance? SecureDirectoryStream root-directory)
          (fail! "Filesystem cannot provide secure project traversal"
                 {:filesystem/feature :secure-directory-stream}))
        (loop [^SecureDirectoryStream directory root-directory
               [component & more] components]
          (if component
            (let [^SecureDirectoryStream child
                  (try
                    (.newDirectoryStream
                     directory component
                     (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                    (catch java.nio.file.NotDirectoryException _
                      (fail! "project/list target is not a directory" {}))
                    (catch java.nio.file.NoSuchFileException _
                      (fail! "project/list target does not exist" {}))
                    ;; Both of the above extend FileSystemException, so this
                    ;; stays last. It is what a symbolic link component
                    ;; raises under NOFOLLOW_LINKS, which is the case that
                    ;; would otherwise walk out of the authorized root.
                    (catch java.nio.file.FileSystemException _
                      (fail! "project/list will not follow a symbolic link"
                             {})))]
              (swap! opened conj child)
              (recur child more))
            (directory-entries directory max-entries))))
      (finally
        (close-directories! @opened)))))

(defn- decode-utf8 [bytes]
  (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                  (.onMalformedInput CodingErrorAction/REPORT)
                  (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (str (.decode decoder (ByteBuffer/wrap bytes)))))

(defn- project-read [runtime effective [relative-path :as args]]
  (when-not (and (= 1 (count args))
                 (string? relative-path)
                 (not (str/blank? relative-path))
                 (<= (count relative-path) 4096))
    (fail! "project/read expects one bounded non-empty relative path"
           {:operation/id :project/read}))
  (let [resource-id (get-in effective [:context/resources :project :resource/id])
        ^Path root (get (:resources runtime) resource-id)
        supplied (Paths/get relative-path (make-array String 0))]
    (when (.isAbsolute supplied)
      (fail! "project/read rejects absolute paths" {:path relative-path}))
    (let [lexical-target (.normalize (.resolve root supplied))]
      (when-not (.startsWith lexical-target root)
        (fail! "project/read path escapes the authorized root"
               {:path relative-path}))
      (when (= lexical-target root)
        (fail! "project/read target is not a regular file"
               {:path relative-path}))
      (decode-utf8
       (secure-project-bytes
        root (.relativize root lexical-target)
        (get-in effective [:context/limits :project/read-max-bytes]))))))

(def ^:private max-search-pattern-characters 200)
(def ^:private max-search-line-characters 300)
(def ^:private search-match-budget
  "Character reads one line may cost the regex engine.

  Measured rather than assumed: Java's matcher does not blow up exponentially
  on the textbook cases -- (a+)+$ against n a's costs O(n^2) reads, 41k at
  n=200 and 1.0M at n=1000, not 2^n. So this is not a defence against
  exponential backtracking, which the engine already avoids. It bounds the
  superlinear case: one long line and a nested quantifier can still burn real
  CPU, and a file full of such lines multiplies it. The matcher reads each
  line through a counting CharSequence and fails past this bound, so search
  cost stays a function of project size rather than of pattern cleverness."
  200000)

(defn- budgeted-sequence [^CharSequence source budget]
  (let [remaining (long-array 1 budget)]
    (reify CharSequence
      (charAt [_ index]
        (when (neg? (aset remaining 0 (dec (aget remaining 0))))
          (fail! "project/search pattern exceeded its matching budget"
                 {:budget budget}))
        (.charAt source index))
      (length [_] (.length source))
      (subSequence [_ start end] (.subSequence source start end))
      (toString [_] (.toString source)))))

(defn- search-line? [^java.util.regex.Pattern pattern ^String line]
  (.find (.matcher pattern (budgeted-sequence line search-match-budget))))

(defn- decode-utf8-or-nil
  "nil for anything that is not valid UTF-8, which is how a binary file is
   recognized without guessing from its name."
  [bytes]
  (try
    (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))]
      (str (.decode decoder (ByteBuffer/wrap bytes))))
    (catch Throwable _ nil)))

(defn- search-file!
  [^SecureDirectoryStream directory ^Path name-path relative-path pattern
   state {:keys [max-results max-file-bytes]}]
  (let [content (try
                  (with-open [^SeekableByteChannel channel
                              (.newByteChannel
                               directory name-path
                               #{StandardOpenOption/READ LinkOption/NOFOLLOW_LINKS}
                               (make-array FileAttribute 0))]
                    (decode-utf8-or-nil
                     (read-bounded-channel channel max-file-bytes)))
                  ;; A file larger than the per-file bound is skipped rather
                  ;; than failing the whole search, which would make one big
                  ;; artefact hide every other match.
                  (catch clojure.lang.ExceptionInfo _ nil))]
    (when content
      (loop [[line & more] (str/split content #"\n" -1)
             number 1]
        (when (and line (< (count (:results @state)) max-results))
          (when (search-line? pattern line)
            (swap! state update :results conj
                   {:path relative-path
                    :line number
                    :text (let [trimmed (str/trim line)]
                            (if (> (count trimmed) max-search-line-characters)
                              (str (subs trimmed 0 max-search-line-characters) "...")
                              trimmed))}))
          (recur more (inc number)))))))

(defn- search-directory!
  [^SecureDirectoryStream directory prefix pattern state
   {:keys [max-results max-files include-hidden?] :as options}]
  (let [entries (vec (iterator-seq (.iterator directory)))]
    (doseq [^Path entry (sort-by str entries)
            :while (< (count (:results @state)) max-results)]
      (let [^Path name-path (.getFileName entry)
            entry-name (str name-path)
            relative (if (str/blank? prefix)
                       entry-name
                       (str prefix "/" entry-name))]
        (when (or include-hidden? (not (str/starts-with? entry-name ".")))
          (let [^BasicFileAttributeView view
                (.getFileAttributeView
                 directory name-path BasicFileAttributeView
                 (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                ^BasicFileAttributes attributes (.readAttributes view)]
            (cond
              ;; Never followed, exactly as in project/list, so a search
              ;; cannot be steered out of the authorized root.
              (.isSymbolicLink attributes) nil

              (.isDirectory attributes)
              (with-open [^SecureDirectoryStream child
                          (.newDirectoryStream
                           directory name-path
                           (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))]
                (search-directory! child relative pattern state options))

              (.isRegularFile attributes)
              (do
                (when (>= (:files @state) max-files)
                  (fail! "project/search exceeded its file limit"
                         {:limit max-files}))
                (swap! state update :files inc)
                (search-file! directory name-path relative pattern state
                              options))

              :else nil)))))))

(defn- project-search [runtime effective [pattern-string options :as args]]
  (when-not (and (<= 1 (count args) 2)
                 (string? pattern-string)
                 (not (str/blank? pattern-string))
                 (<= (count pattern-string) max-search-pattern-characters)
                 (or (nil? options) (map? options)))
    (fail! "project/search expects a bounded pattern and an optional options map"
           {:operation/id :project/search}))
  (let [{:keys [path include-hidden?]} options
        path (or path ".")]
    (when-not (and (string? path) (not (str/blank? path))
                   (<= (count path) 4096))
      (fail! "project/search :path must be a bounded relative path" {}))
    (let [resource-id (get-in effective [:context/resources :project :resource/id])
          ^Path root (get (:resources runtime) resource-id)
          supplied (Paths/get path (make-array String 0))]
      (when (.isAbsolute supplied)
        (fail! "project/search rejects absolute paths" {:path path}))
      (let [lexical-target (.normalize (.resolve root supplied))]
        (when-not (.startsWith lexical-target root)
          (fail! "project/search path escapes the authorized root" {:path path}))
        (let [pattern (try
                        (java.util.regex.Pattern/compile pattern-string)
                        (catch java.util.regex.PatternSyntaxException _
                          (fail! "project/search pattern is not a valid regex"
                                 {:pattern pattern-string})))
              limits (:context/limits effective)
              options {:max-results (:project/search-max-results limits)
                       :max-files (:project/search-max-files limits)
                       :max-file-bytes (:project/read-max-bytes limits)
                       :include-hidden? (true? include-hidden?)}
              state (atom {:results [] :files 0})
              relative-target (.relativize root lexical-target)
              components (into []
                               (comp (map #(.getName relative-target %))
                                     (remove #(str/blank? (str %))))
                               (range (.getNameCount relative-target)))
              opened (atom [])]
          (try
            (let [root-directory (Files/newDirectoryStream root)]
              (swap! opened conj root-directory)
              (when-not (instance? SecureDirectoryStream root-directory)
                (fail! "Filesystem cannot provide secure project traversal"
                       {:filesystem/feature :secure-directory-stream}))
              (loop [^SecureDirectoryStream directory root-directory
                     [component & more] components]
                (if component
                  (let [^SecureDirectoryStream child
                        (try
                          (.newDirectoryStream
                           directory component
                           (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                          (catch java.nio.file.NotDirectoryException _
                            (fail! "project/search :path is not a directory" {}))
                          (catch java.nio.file.NoSuchFileException _
                            (fail! "project/search :path does not exist" {}))
                          (catch java.nio.file.FileSystemException _
                            (fail! "project/search will not follow a symbolic link"
                                   {})))]
                    (swap! opened conj child)
                    (recur child more))
                  (do (search-directory! directory
                                         (str (.relativize root lexical-target))
                                         pattern state options)
                      (:results @state)))))
            (finally
              (close-directories! @opened))))))))

(defn- project-list [runtime effective [relative-path :as args]]
  (when-not (and (= 1 (count args))
                 (string? relative-path)
                 (not (str/blank? relative-path))
                 (<= (count relative-path) 4096))
    (fail! "project/list expects one bounded non-empty relative path"
           {:operation/id :project/list}))
  (let [resource-id (get-in effective [:context/resources :project :resource/id])
        ^Path root (get (:resources runtime) resource-id)
        supplied (Paths/get relative-path (make-array String 0))]
    (when (.isAbsolute supplied)
      (fail! "project/list rejects absolute paths" {:path relative-path}))
    (let [lexical-target (.normalize (.resolve root supplied))]
      (when-not (.startsWith lexical-target root)
        (fail! "project/list path escapes the authorized root"
               {:path relative-path}))
      ;; Unlike reading, the root itself is a legitimate target: listing it
      ;; is the operation's primary use.
      (secure-project-listing
       root (.relativize root lexical-target)
       (get-in effective [:context/limits :project/list-max-entries])))))

(def ^:private implementations
  {:bb4t.data/json-read (fn [_runtime _effective args] (json-read args))
   :bb4t.data/json-write (fn [_runtime _effective args] (json-write args))
   :bb4t.project/read project-read
   :bb4t.project/list project-list
   :bb4t.project/search project-search})

(defn- timestamp []
  (str (Instant/now)))

(defn- emit! [runtime context-coordinate instance-id event-type data]
  (let [event-base {:event/id (str (UUID/randomUUID))
                    :event/type event-type
                    :runtime/coordinate (:coordinate runtime)
                    :context/coordinate context-coordinate
                    :context/instance-id instance-id
                    :timestamp (timestamp)
                    :data data}
        event-state
        (swap! (:event-state runtime)
               (fn [{:keys [events dropped next-seq]}]
                 (let [event (assoc event-base :event/seq (inc next-seq))
                       next-events (conj events event)
                       excess (max 0 (- (count next-events)
                                        (:event-limit runtime)))]
                   {:events (if (pos? excess)
                              (into [] (subvec next-events excess))
                              next-events)
                    :dropped (+ dropped excess)
                    :next-seq (:event/seq event)})))
        event (peek (:events event-state))]
    (doseq [[_ subscriber] @(:subscribers runtime)]
      (try
        (subscriber event)
        (catch Throwable _)))
    event))

(defn- resolve-root [resource-id root]
  (when-not (= :project/root resource-id)
    (fail! "Unknown runtime resource" {:resource/id resource-id}))
  (when-not (or (string? root) (instance? Path root))
    (fail! "Runtime resource root must be a path string or Path"
           {:resource/id resource-id}))
  (let [path (if (instance? Path root)
               root
               (Paths/get ^String root (make-array String 0)))
        real-root (.toRealPath ^Path path (make-array LinkOption 0))]
    (when-not (Files/isDirectory real-root (make-array LinkOption 0))
      (fail! "Runtime resource root must be a directory"
             {:resource/id resource-id}))
    real-root))

(defn- create-runtime-state
  [opts]
  (exact-keys! :runtime-options opts #{:resources :event-limit})
  (let [resource-input (or (:resources opts) {})
        event-limit (or (:event-limit opts) default-event-limit)]
    (when-not (map? resource-input)
      (fail! "Runtime resources must be a map" {}))
    (when-not (and (integer? event-limit) (pos? event-limit))
      (fail! "Event limit must be a positive integer" {:event-limit event-limit}))
    (validate-catalog catalog/capability-catalog (set (keys implementations)))
    (let [resources (into {} (map (fn [[id root]] [id (resolve-root id root)]))
                          resource-input)
           resource-descriptions
           (into {} (map (fn [[id ^Path root]]
                           [id {:resource/id id
                                :resource/type :filesystem/root
                                :resource/path (str root)}]))
                 resources)
           manifest {:manifest/version 1
                     :manifest/type :bb4t/runtime-manifest
                     :bb4t/commit (build-commit)
                     :upstream/commit upstream-commit
                     :sci/commit sci-commit
                     :compiled/universe
                     {:distribution :babashka/upstream-baseline
                      :version "1.13.219"
                      :source/commit upstream-commit
                      :feature-selection :upstream/default}
                     :catalogued/libraries #{:cheshire}
                     :compiled/capabilities
                     (set (keys (:capabilities catalog/capability-catalog)))
                     :sci/base {:construction :fresh
                                :authorization :positive-allowlist
                                :allow catalog/base-allow
                                :default-interop-deny catalog/base-deny
                                :unrestricted false
                                :projected/classes 0
                                :default-class-overrides
                                catalog/closed-default-classes
                                :supplied/imports 0
                                :defaults :pinned-sci-defaults}}
          runtime-coordinate (canonical/coordinate :bb4t/runtime manifest)
          catalog-coordinate
          (canonical/coordinate :bb4t/catalog catalog/capability-catalog)
          runtime (->RuntimeState manifest runtime-coordinate
                                  catalog/capability-catalog catalog-coordinate
                                  implementations resources resource-descriptions
                                  event-limit
                                  (atom {:events [] :dropped 0 :next-seq 0})
                                  (atom {}))]
      (emit! runtime nil nil :runtime/created
             {:catalog/coordinate catalog-coordinate})
      runtime)))

(defn create-runtime
  "Constructs an opaque runtime handle from compiled data and resource bindings."
  [opts]
  (register-handle runtime-handles (create-runtime-state opts)))

(defn runtime-description [runtime]
  (let [runtime (resolve-handle runtime-handles :runtime runtime)]
    {:runtime/manifest (:manifest runtime)
     :runtime/coordinate (:coordinate runtime)
     :catalog/coordinate (:catalog-coordinate runtime)
     :runtime/resources (set (keys (:resource-descriptions runtime)))}))

(defn catalog-description [runtime]
  (let [runtime (resolve-handle runtime-handles :runtime runtime)]
    {:catalog/data (:catalog runtime)
     :catalog/coordinate (:catalog-coordinate runtime)}))

(defn- resolve-context-spec [runtime input]
  (exact-keys! :context-spec input
               #{:context-spec/version :profile :requested-capabilities
                 :authorized-capabilities :resource-bindings :limits})
  (let [version (or (:context-spec/version input) 1)
        profile-id (:profile input)
        profile (catalog/profile profile-id)]
    (when-not (= 1 version)
      (fail! "Unsupported ContextSpec version" {:context-spec/version version}))
    (when-not profile
      (fail! "Unknown context profile" {:profile profile-id}))
    (let [profile-max (:profile/max-capabilities profile)
          requested (or (:requested-capabilities input) profile-max)
          authorized (or (:authorized-capabilities input) profile-max)
          _ (qualified-keyword-set! :requested-capabilities requested)
          _ (qualified-keyword-set! :authorized-capabilities authorized)
          compiled (:compiled/capabilities (:manifest runtime))]
      (when-not (every? profile-max authorized)
        (fail! "Authorized capabilities exceed the profile maximum"
               {:profile profile-id :authorized authorized :profile/max profile-max}))
      (when-not (every? authorized requested)
        (fail! "Requested capabilities exceed authorization"
               {:requested requested :authorized authorized}))
      (when-not (every? compiled authorized)
        (fail! "Authorized capabilities are not compiled"
               {:authorized authorized :compiled compiled}))
      (let [;; Each project capability contributes the limit keys its own
            ;; implementation enforces, so a context carries exactly the
            ;; limits its grants use -- no more, and never fewer.
            requested-project (filterv #(contains? catalog/project-capabilities %)
                                       requested)
            project? (boolean (seq requested-project))
            required-limits (into #{}
                                  (mapcat #(get-in catalog/project-capabilities
                                                   [% :limits]))
                                  requested-project)
            default-bindings (if project? (:profile/resources profile) {})
            resource-bindings (or (:resource-bindings input) default-bindings)
            default-limits (select-keys (:profile/limits profile) required-limits)
            limits (or (:limits input) default-limits)]
        (when-not (= (if project? #{:project} #{})
                     (set (keys resource-bindings)))
          (fail! "Context resource bindings do not match effective grants"
                 {:resource-bindings resource-bindings :requested requested}))
        (when-not (= required-limits (set (keys limits)))
          (fail! "Context limits do not match effective grants"
                 {:limits limits :requested requested}))
        (when project?
          (when-not (= :project/root (:project resource-bindings))
            (fail! "Project capability requires the trusted project resource"
                   {:resource-bindings resource-bindings}))
          (when-not (contains? (:resources runtime) :project/root)
            (fail! "Runtime has no project resource" {}))
          (doseq [limit-key required-limits]
            (let [value (get limits limit-key)
                  profile-max (get-in profile [:profile/limits limit-key])]
              (when-not (and (integer? value)
                             (pos? value)
                             (integer? profile-max)
                             (<= value profile-max))
                (fail! "Project limit exceeds the profile maximum"
                       {:limit/key limit-key
                        :limit value
                        :profile/max profile-max})))))
        {:context-spec/version version
         :profile profile-id
         :requested-capabilities requested
         :authorized-capabilities authorized
         :resource-bindings resource-bindings
         :limits limits}))))

(defn- operation-entry [runtime operation-id]
  (some (fn [[capability-id capability]]
          (when (= operation-id (get-in capability [:operation :operation/id]))
            [capability-id capability]))
        (get-in runtime [:catalog :capabilities])))

(defn- invoke-authorized
  [runtime effective context-coordinate instance-id operation-id args]
  (let [[capability-id capability] (operation-entry runtime operation-id)]
    (when-not capability
      (emit! runtime context-coordinate instance-id :operation/denied
             {:operation/id operation-id :reason :unknown-operation})
      (fail! "Unknown semantic operation" {:operation/id operation-id}))
    (when-not (contains? (:context/grants effective) capability-id)
      (emit! runtime context-coordinate instance-id :operation/denied
             {:operation/id operation-id
              :capability/id capability-id
              :reason :not-granted})
      (throw (ex-info "Semantic operation is not granted"
                      {:bb4t/error :unauthorized
                       :operation/id operation-id
                       :capability/id capability-id})))
    (let [implementation-id (:implementation/id capability)
          implementation (get (:implementations runtime) implementation-id)]
      (try
        (let [result (implementation runtime effective args)]
          (emit! runtime context-coordinate instance-id :operation/completed
                 {:operation/id operation-id
                  :capability/id capability-id
                  :status :ok})
          result)
        (catch Throwable error
          (emit! runtime context-coordinate instance-id :operation/failed
                 {:operation/id operation-id
                  :capability/id capability-id
                  :status :error
                  :error/type (.getName (class error))})
          (if (or (not (instance? Exception error))
                  (:bb4t/error (ex-data error)))
            (throw error)
            (throw (ex-info "Semantic operation failed"
                            {:bb4t/error :operation-failed
                             :operation/id operation-id
                             :capability/id capability-id
                             :error/type (.getName (class error))}
                            error))))))))

(defn- projection-data [runtime effective coordinate instance-id]
  (let [{:keys [projections] :as projected}
        (reduce
         (fn [{:keys [namespaces] :as result} capability-id]
           (let [capability (get-in runtime [:catalog :capabilities capability-id])
                 operation (:operation capability)
                 ns-symbol (:sci/namespace operation)
                 var-symbol (:sci/var operation)
                 operation-id (:operation/id operation)
                 sci-namespace (or (get-in namespaces [ns-symbol ::namespace])
                                   (sci/create-ns ns-symbol nil))
                 implementation
                 (fn [& args]
                   (invoke-authorized runtime effective coordinate instance-id
                                      operation-id args))
                 sci-var
                 (sci/new-var var-symbol implementation
                              {:ns sci-namespace
                               :doc (:doc operation)
                               :arglists (:arglists operation)
                               :bb4t/capability capability-id
                               :bb4t/effects (:effects capability)})
                 qualified-var (symbol (str ns-symbol) (str var-symbol))]
             (-> result
                 (assoc-in [:namespaces ns-symbol ::namespace] sci-namespace)
                 (assoc-in [:namespaces ns-symbol var-symbol] sci-var)
                 (update :projections conj
                         {:capability/id capability-id
                          :operation/id operation-id
                          :sci/var qualified-var
                          :effects (:effects capability)
                          :doc (:doc operation)
                          :arglists (:arglists operation)})
                 (update :allow conj qualified-var))))
         {:namespaces {} :projections [] :allow catalog/base-allow}
         (sort (:context/grants effective)))
        projected-vars (mapv :sci/var projections)
        documentation
        (into {} (map (fn [{:keys [sci/var effects doc arglists]}]
                        [var (str "-------------------------\n"
                                  var "\n"
                                  arglists "\n"
                                  " " doc "\n"
                                  " effects: " effects)]))
              projections)
        user-namespace (sci/create-ns 'user nil)
        apropos-var
        (sci/new-var 'apropos
                     (fn [query]
                       (filterv #(str/includes? (str %) (str query))
                                projected-vars))
                     {:ns user-namespace
                      :doc "Find granted semantic operations by name."
                      :arglists (list ['query])})
        doc-var
        (sci/new-macro-var 'doc
                           (fn [_form _environment symbol]
                             `(println ~(or (get documentation symbol)
                                           (str "No documentation for " symbol))))
                           {:ns user-namespace
                            :doc "Print documentation for a granted operation."
                            :arglists (list ['symbol])})]
    (-> projected
        (assoc-in [:namespaces 'user ::namespace] user-namespace)
        (assoc-in [:namespaces 'user 'apropos] apropos-var)
        (assoc-in [:namespaces 'user 'doc] doc-var))))

(defn- create-context-state
  [runtime context-spec]
  (let [spec (resolve-context-spec runtime context-spec)
        spec-coordinate (canonical/coordinate :bb4t/context-spec spec)
        resource-descriptions
        (into {}
              (map (fn [[logical-name resource-id]]
                     [logical-name (get (:resource-descriptions runtime) resource-id)]))
              (:resource-bindings spec))
        effective {:context/version 1
                   :runtime/coordinate (:coordinate runtime)
                   :catalog/coordinate (:catalog-coordinate runtime)
                   :context/profile (:profile spec)
                   :context/grants (:requested-capabilities spec)
                   :context/resources resource-descriptions
                   :context/limits (:limits spec)}
        coordinate (canonical/coordinate :bb4t/context effective)
        instance-id (str (UUID/randomUUID))
        {:keys [namespaces projections allow]}
        (projection-data runtime effective coordinate instance-id)
        sci-namespaces
        (into {} (map (fn [[ns-symbol vars]]
                        [ns-symbol (dissoc vars ::namespace)]))
              namespaces)
        sci-context (sci/init {:namespaces sci-namespaces
                               :allow allow
                               ;; str/ is how Clojure is written, and the
                               ;; bounded context has no require or alias with
                               ;; which to establish it. Without this the
                               ;; caller reaches clojure.string only by its
                               ;; full name, which the A2 dogfood showed the
                               ;; model getting wrong on its first attempt.
                               ;; An alias renames access to vars that are
                               ;; already allowed; it grants nothing, which
                               ;; the authority corpus checks.
                               :ns-aliases catalog/base-ns-aliases
                               :deny catalog/base-deny
                               :classes catalog/closed-default-classes
                               :unrestricted false})
        context (->ContextState runtime instance-id spec spec-coordinate effective
                                coordinate sci-context (Object.) projections allow)]
    (emit! runtime coordinate instance-id :context/created
           {:profile (:profile spec)
            :grants (:requested-capabilities spec)})
    context))

(defn create-context
  "Constructs an opaque handle for a fresh fail-closed SCI context."
  [runtime context-spec]
  (let [runtime (resolve-handle runtime-handles :runtime runtime)]
    (register-handle context-handles
                     (create-context-state runtime context-spec))))

(defn context-description [context]
  (let [context (resolve-handle context-handles :context context)]
    {:context/spec (:spec context)
     :context-spec/coordinate (:spec-coordinate context)
     :context/effective (:effective context)
     :context/coordinate (:coordinate context)
     :context/instance-id (:instance-id context)
     :context/surface {:allow (:allow context)
                       :deny catalog/base-deny
                       :projections (:projections context)
                       :base-namespace-count 1
                       :base-var-count 2
                       :capability-projection-namespace-count
                       (count (set (map (comp namespace :sci/var)
                                        (:projections context))))
                       :capability-projection-var-count
                       (count (:projections context))
                       :total-projected-namespace-count
                       (inc (count (set (map (comp namespace :sci/var)
                                             (:projections context)))))
                       :total-projected-var-count
                       (+ 2 (count (:projections context)))
                       :projected-class-count 0
                       :closed-default-class-count
                       (count catalog/closed-default-classes)
                       :supplied-import-count 0}}))

(defn evaluate
  "Evaluates source in one persistent context and returns structured output."
  [context source]
  (let [context (resolve-handle context-handles :context context)]
    (when-not (string? source)
      (fail! "Context evaluation requires source text" {}))
    (locking (:lock context)
      (let [out (StringWriter.)
            err (StringWriter.)]
        (try
          (let [{:keys [value origin]}
                (sci/binding [sci/out out sci/err err]
                  ;; Realized here, not at description time: forcing runs the
                  ;; sequence's own computation, which belongs inside the
                  ;; out/err capture and before the success event.
                  (value/realize
                   (sci/eval-string* (:sci-context context) source)))]
            (emit! (:runtime context) (:coordinate context) (:instance-id context)
                   :context/evaluated {:status :ok})
            {:value (value/describe value origin)
             :out (str out) :err (str err)})
          (catch Throwable error
            (emit! (:runtime context) (:coordinate context) (:instance-id context)
                   :context/evaluation-failed
                   {:status :error :error/type (.getName (class error))})
            (throw error)))))))

(defn invoke [context operation-id args]
  (let [context (resolve-handle context-handles :context context)]
    (when-not (vector? args)
      (fail! "Semantic operation arguments must be a vector" {:args args}))
    (value/describe
     (invoke-authorized (:runtime context) (:effective context)
                        (:coordinate context) (:instance-id context)
                        operation-id args))))

(defn event-snapshot [runtime]
  (let [runtime (resolve-handle runtime-handles :runtime runtime)
        {:keys [events dropped]} @(:event-state runtime)]
    {:events events
     :events/dropped dropped}))

(defn context-event-snapshot [context]
  (let [context (resolve-handle context-handles :context context)
        {:keys [events]} @(:event-state (:runtime context))]
    {:events (filterv #(= (:instance-id context) (:context/instance-id %))
                      events)}))

(defn subscribe [runtime subscriber]
  (let [runtime (resolve-handle runtime-handles :runtime runtime)]
    (when-not (ifn? subscriber)
      (fail! "Event subscriber must be callable" {}))
    (let [subscriber-id (str (UUID/randomUUID))]
      (swap! (:subscribers runtime) assoc subscriber-id subscriber)
      (fn unsubscribe []
        (swap! (:subscribers runtime) dissoc subscriber-id)
        nil))))
