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
   projections allow transcript])

(defrecord Transcript [mode state])

(defn- fail! [message data]
  (throw (ex-info message (assoc data :bb4t/error :validation))))

(defn- transcript-fail!
  "Fails an evaluation closed because its transcript did not account for what
   the form did.

  A distinct :bb4t/error, because this is not the bounded context refusing an
  operation: it is recovery refusing to guess.  The caller has to be able to
  tell the two apart, or a replay that diverged would be indistinguishable
  from a form that legitimately failed the same way it always did."
  [reason message data]
  (throw (ex-info message (merge data {:bb4t/error :transcript
                                       :transcript/error reason}))))

(defn create-recorder
  "A transcript that records every semantic operation an evaluation invokes."
  []
  (->Transcript :record (atom {:operations []})))

(defn create-player
  "A transcript that reproduces recorded operations instead of invoking them."
  [receipts]
  (when-not (and (sequential? receipts) (every? map? receipts))
    (fail! "Operation receipts must be a sequence of maps" {}))
  (->Transcript :replay (atom {:receipts (vec receipts) :cursor 0})))

(defn create-legacy
  "A transcript for a form recorded before transcripts existed.

  There is no receipt to reproduce, so an operation runs against the live
  world.  That is tolerable for an observation, which at worst answers a newer
  question, and is not tolerable for an actuation, which would be a second
  change.  Observations are counted so the caller can state plainly that the
  reconstruction was approximate rather than exact."
  []
  (->Transcript :legacy (atom {:observations []})))

(defn transcript? [value] (instance? Transcript value))

(defn transcript-operations
  "The receipts a recording transcript captured, in invocation order."
  [transcript]
  (:operations @(:state transcript)))

(defn transcript-observations
  "The operation IDs a legacy transcript re-observed, in invocation order."
  [transcript]
  (:observations @(:state transcript)))

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
      ;; Recovery decides whether a historical operation may be re-run from
      ;; this classification, so an unclassified effect is a catalog error
      ;; rather than a capability that quietly defaults to re-executable.
      (doseq [effect (:effects capability)]
        (when-not (contains? catalog/effect-kinds (catalog/effect-kind effect))
          (fail! "Capability declares an unclassified effect"
                 {:capability/id capability-id :effect effect})))
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

(defn- sha256-digest [^bytes content]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.digest digest content)]
    (str "sha256:"
         (str/join (map #(format "%02x" %) bytes)))))

(defn- descend
  "Walks components under root through SecureDirectoryStreams and calls f with
   the innermost directory. Never follows a symbolic link, so no component can
   redirect the walk out of the authorized root."
  [^Path root components operation f]
  (let [opened (atom [])]
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
                      (fail! (str operation " path component is not a directory")
                             {}))
                    (catch java.nio.file.NoSuchFileException _
                      (fail! (str operation " path does not exist") {}))
                    (catch java.nio.file.FileSystemException _
                      (fail! (str operation " will not follow a symbolic link")
                             {})))]
              (swap! opened conj child)
              (recur child more))
            (f directory))))
      (finally
        (close-directories! @opened)))))

(defn- project-path
  "Validates a relative path and returns [directory-components file-name]."
  [runtime effective operation relative-path]
  (when-not (and (string? relative-path)
                 (not (str/blank? relative-path))
                 (<= (count relative-path) 4096))
    (fail! (str operation " expects one bounded non-empty relative path")
           {:path relative-path}))
  (let [resource-id (get-in effective [:context/resources :project :resource/id])
        ^Path root (get (:resources runtime) resource-id)
        supplied (Paths/get relative-path (make-array String 0))]
    (when (.isAbsolute supplied)
      (fail! (str operation " rejects absolute paths") {:path relative-path}))
    (let [lexical-target (.normalize (.resolve root supplied))]
      (when-not (.startsWith lexical-target root)
        (fail! (str operation " path escapes the authorized root")
               {:path relative-path}))
      (when (= lexical-target root)
        (fail! (str operation " target is the project root, not a file")
               {:path relative-path}))
      (let [relative (.relativize root lexical-target)
            components (mapv #(.getName relative %)
                             (range (.getNameCount relative)))]
        [root (vec (butlast components)) (last components)
         (str relative)]))))

(defn- current-file-bytes
  "The file's bytes, or nil when it does not exist."
  [^SecureDirectoryStream directory ^Path name-path max-bytes]
  (try
    (with-open [^SeekableByteChannel channel
                (.newByteChannel
                 directory name-path
                 #{StandardOpenOption/READ LinkOption/NOFOLLOW_LINKS}
                 (make-array FileAttribute 0))]
      (read-bounded-channel channel max-bytes))
    (catch java.nio.file.NoSuchFileException _ nil)))

(defn- project-stat [runtime effective [relative-path :as args]]
  (when-not (= 1 (count args))
    (fail! "project/stat expects one relative path" {:operation/id :project/stat}))
  (let [[root components name-path relative]
        (project-path runtime effective "project/stat" relative-path)
        max-bytes (get-in effective [:context/limits :project/read-max-bytes])]
    (descend
     root components "project/stat"
     (fn [^SecureDirectoryStream directory]
       (let [^BasicFileAttributeView view
             (.getFileAttributeView
              directory name-path BasicFileAttributeView
              (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
             ^BasicFileAttributes attributes
             (try (.readAttributes view)
                  (catch java.nio.file.NoSuchFileException _ nil))]
         (if (nil? attributes)
           {:path relative :kind :absent}
           (let [kind (entry-kind attributes)]
             (if (= :file kind)
               {:path relative
                :kind :file
                :bytes (.size attributes)
                :digest (sha256-digest
                         (current-file-bytes directory name-path max-bytes))}
               {:path relative :kind kind}))))))))

(defn- project-edit [runtime effective [options :as args]]
  (when-not (and (= 1 (count args)) (map? options))
    (fail! "project/edit expects one options map"
           {:operation/id :project/edit}))
  (let [{:keys [path base content]} options
        [root components name-path relative]
        (project-path runtime effective "project/edit" path)
        limits (:context/limits effective)
        max-write (:project/write-max-bytes limits)
        max-read (:project/read-max-bytes limits)]
    (when-not (string? content)
      (fail! "project/edit :content must be a string" {:path relative}))
    (when-not (or (= :absent base)
                  (and (map? base) (string? (:digest base))))
      (fail! (str "project/edit :base must be :absent or {:digest \"sha256:...\"}; "
                  "an edit without a base coordinate is a blind overwrite")
             {:path relative}))
    (let [encoded (.getBytes ^String content StandardCharsets/UTF_8)]
      (when (> (alength encoded) max-write)
        (fail! "project/edit content exceeds the write byte limit"
               {:limit max-write :bytes (alength encoded)}))
      (descend
       root components "project/edit"
       (fn [^SecureDirectoryStream directory]
         (let [^BasicFileAttributeView view
               (.getFileAttributeView
                directory name-path BasicFileAttributeView
                (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
               ^BasicFileAttributes attributes
               (try (.readAttributes view)
                    (catch java.nio.file.NoSuchFileException _ nil))]
           (when (and attributes (not (.isRegularFile attributes)))
             (fail! "project/edit target is not a regular file" {:path relative}))
           ;; Version anchoring. The world can change under an agent between a
           ;; read and a write -- a human editor, a formatter, a Git checkout --
           ;; so an edit states what it believed and is refused when that is no
           ;; longer true.
           (let [observed (when attributes
                            (sha256-digest
                             (current-file-bytes directory name-path max-read)))]
             (cond
               (and (= :absent base) attributes)
               (fail! "project/edit conflict: file exists but :base was :absent"
                      {:path relative :conflict/observed observed
                       :bbagent/conflict true})

               (and (not= :absent base) (nil? attributes))
               (fail! "project/edit conflict: file does not exist"
                      {:path relative :conflict/expected (:digest base)
                       :bbagent/conflict true})

               (and (not= :absent base) (not= observed (:digest base)))
               (fail! "project/edit conflict: file changed since it was read"
                      {:path relative
                       :conflict/expected (:digest base)
                       :conflict/observed observed
                       :bbagent/conflict true})

               :else
               ;; Written to a sibling temporary and renamed, so a reader never
               ;; observes a partially written file and a failed write leaves
               ;; the original intact.
               (let [temp-name (Paths/get (str ".bbagent-edit-"
                                               (UUID/randomUUID))
                                          (make-array String 0))]
                 (try
                   (with-open [^SeekableByteChannel channel
                               (.newByteChannel
                                directory temp-name
                                #{StandardOpenOption/WRITE
                                  StandardOpenOption/CREATE_NEW}
                                (make-array FileAttribute 0))]
                     (.write channel (ByteBuffer/wrap encoded)))
                   (.move directory temp-name directory name-path)
                   {:path relative
                    :bytes (alength encoded)
                    :digest (sha256-digest encoded)}
                   (catch Throwable failure
                     (try (.deleteFile directory temp-name)
                          (catch Throwable _))
                     (throw failure))))))))))))

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
   :bb4t.project/search project-search
   :bb4t.project/stat project-stat
   :bb4t.project/edit project-edit})

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

(defn- inert-error-data
  "The part of a failure's data that can be written down and read back.

  A kernel failure carries qualified keywords, bounded numbers and relative
  paths, all of which survive.  An entry that does not is dropped rather than
  reconstructed approximately, because a recovery that reproduced a nearly
  right failure would be worse than one that reproduced a plainly partial
  one."
  [data]
  (when (map? data)
    (into {}
          (keep (fn [[key value]]
                  (try
                    (canonical/canonical-string [key value])
                    [key value]
                    (catch Throwable _ nil))))
          data)))

(defn- recorded-outcome
  "One receipt's account of what an operation returned or threw.

  A successful result is written down with its own strict coordinate.  The
  journal that stores it is free to rewrite large strings as content
  references and to strip entries that look like secrets; the coordinate is
  taken here, before any of that, so a value that came back changed fails
  closed on replay instead of being reconstructed wrong."
  [{:keys [status result error]}]
  (if (= :ok status)
    (try
      {:status :ok
       :result result
       :result/digest (canonical/coordinate :bb4t/operation-result result)}
      (catch Throwable _
        {:status :ok
         :result/opaque true
         :result/type (some-> result class .getName)}))
    {:status :error
     :error/message (ex-message error)
     :error/type (.getName (class error))
     :error/data (inert-error-data (ex-data error))}))

(defn- args-digest [args]
  (canonical/lenient-coordinate :bb4t/operation-args (vec args)))

(defn- actuating? [capability]
  (boolean (some #(= :actuation (catalog/effect-kind %)) (:effects capability))))

(defn- run-implementation!
  [runtime effective context-coordinate instance-id operation-id capability-id
   capability args]
  (let [implementation (get (:implementations runtime)
                            (:implementation/id capability))]
    (try
      (let [result (implementation runtime effective args)]
        (emit! runtime context-coordinate instance-id :operation/completed
               {:operation/id operation-id
                :capability/id capability-id
                :status :ok})
        {:status :ok :result result})
      (catch Throwable error
        (emit! runtime context-coordinate instance-id :operation/failed
               {:operation/id operation-id
                :capability/id capability-id
                :status :error
                :error/type (.getName (class error))})
        {:status :error
         :error (if (or (not (instance? Exception error))
                        (:bb4t/error (ex-data error)))
                  error
                  (ex-info "Semantic operation failed"
                           {:bb4t/error :operation-failed
                            :operation/id operation-id
                            :capability/id capability-id
                            :error/type (.getName (class error))}
                           error))}))))

(defn- replay-operation!
  "Reproduces one recorded operation without reaching the world.

  Identity and arguments are checked before the recorded outcome is handed
  back, so a replay that took a different branch, called a different
  operation, or called the same operation with different arguments stops here
  rather than continuing on a receipt that was never about it."
  [runtime context-coordinate instance-id transcript operation-id capability-id
   args]
  (let [{:keys [receipts cursor]} @(:state transcript)]
    (when (>= cursor (count receipts))
      (transcript-fail!
       :exhausted
       "Replay invoked more semantic operations than the transcript recorded"
       {:operation/id operation-id
        :transcript/index cursor
        :transcript/count (count receipts)}))
    (let [receipt (nth receipts cursor)
          digest (args-digest args)]
      (when-not (= operation-id (:operation/id receipt))
        (transcript-fail!
         :operation-mismatch
         "Replay invoked a different semantic operation than it recorded"
         {:transcript/index cursor
          :transcript/expected (:operation/id receipt)
          :transcript/actual operation-id}))
      (when-not (= digest (:args/digest receipt))
        (transcript-fail!
         :args-mismatch
         "Replay invoked a semantic operation with different arguments"
         {:operation/id operation-id
          :transcript/index cursor
          :transcript/expected (:args/digest receipt)
          :transcript/actual digest}))
      (swap! (:state transcript) update :cursor inc)
      (emit! runtime context-coordinate instance-id :operation/replayed
             {:operation/id operation-id
              :capability/id capability-id
              :status (:status receipt)
              :transcript/index cursor})
      (case (:status receipt)
        :ok
        (if (contains? receipt :result)
          (let [result (:result receipt)
                digest (try (canonical/coordinate :bb4t/operation-result result)
                            (catch Throwable _ nil))]
            (when-not (= digest (:result/digest receipt))
              (transcript-fail!
               :result-integrity
               "A recorded operation result did not survive storage intact"
               {:operation/id operation-id
                :transcript/index cursor
                :transcript/expected (:result/digest receipt)
                :transcript/actual digest}))
            result)
          (transcript-fail!
           :opaque-result
           "A recorded operation result was not inert enough to reconstruct"
           {:operation/id operation-id :transcript/index cursor}))

        :error
        (throw (ex-info (str (:error/message receipt))
                        (assoc (:error/data receipt) :bb4t/replayed true)))

        (transcript-fail! :malformed-receipt
                          "An operation receipt has no recorded status"
                          {:operation/id operation-id
                           :transcript/index cursor})))))

(defn- invoke-authorized
  [runtime effective context-coordinate instance-id transcript operation-id args]
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
    ;; Authorization is checked before replay, not after.  A recorded result
    ;; is still authority over this project, and a context that no longer
    ;; grants the capability must not be handed one.
    (if (= :replay (:mode transcript))
      (replay-operation! runtime context-coordinate instance-id transcript
                         operation-id capability-id args)
      (do
        (when (= :legacy (:mode transcript))
          (when (actuating? capability)
            (transcript-fail!
             :actuation-without-transcript
             "A historical form would change the project again, and records no receipt for the change it already made"
             {:operation/id operation-id :capability/id capability-id}))
          (swap! (:state transcript) update :observations conj operation-id))
        (let [outcome (run-implementation! runtime effective context-coordinate
                                           instance-id operation-id capability-id
                                           capability args)]
          (when (= :record (:mode transcript))
            (swap! (:state transcript) update :operations conj
                   (merge {:operation/id operation-id
                           :capability/id capability-id
                           :effects (:effects capability)
                           :args/digest (args-digest args)}
                          (recorded-outcome outcome))))
          (if (= :ok (:status outcome))
            (:result outcome)
            (throw (:error outcome))))))))

(defn- projection-data [runtime effective coordinate instance-id transcript]
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
                                      @transcript operation-id args))
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
        ;; One slot per Context, set for the duration of one evaluation and
        ;; cleared after it.  The projections close over the slot rather than
        ;; over a transcript, so recording and replay are properties of an
        ;; evaluation and never of the Context itself.
        transcript (atom nil)
        {:keys [namespaces projections allow]}
        (projection-data runtime effective coordinate instance-id transcript)
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
                                coordinate sci-context (Object.) projections allow
                                transcript)]
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

(defn- transcript-failure
  "The transcript frame of a failure, or nil.

  SCI rethrows an evaluation failure with its own location data and keeps the
  original as a cause, so a transcript refusal raised inside a projection
  arrives wrapped.  Recovery has to be able to tell a refusal to guess from a
  form that legitimately failed, so the frame is found rather than inferred
  from the outermost exception."
  [error]
  (loop [current error
         depth 0]
    (cond
      (or (nil? current) (> depth 8)) nil
      (= :transcript (:bb4t/error (ex-data current))) current
      :else (recur (ex-cause current) (inc depth)))))

(defn- verify-transcript-consumed!
  "Fails closed unless a replay used exactly the receipts it was given.

  Too few is a form that took a different path than the one recorded; too many
  is caught at the call itself.  Either way the reconstruction is not the
  history, and a partly-reconstructed Context is worse than a refused one."
  [transcript]
  (when (= :replay (:mode transcript))
    (let [{:keys [receipts cursor]} @(:state transcript)]
      (when-not (= cursor (count receipts))
        (transcript-fail!
         :unconsumed
         "Replay invoked fewer semantic operations than the transcript recorded"
         {:transcript/index cursor
          :transcript/count (count receipts)})))))

(defn evaluate
  "Evaluates source in one persistent context and returns structured output.

  With a transcript, the same source runs against the same Context and the
  semantic operations it invokes are either recorded or reproduced.  Nothing
  about the evaluation itself changes: ordinary Clojure computes exactly as it
  did, and only the operation boundary behaves differently."
  ([context source] (evaluate context source nil))
  ([context source transcript]
   (let [context (resolve-handle context-handles :context context)]
     (when-not (string? source)
       (fail! "Context evaluation requires source text" {}))
     (when-not (or (nil? transcript) (transcript? transcript))
       (fail! "Context evaluation transcript is not a bb4t transcript" {}))
     (locking (:lock context)
       (reset! (:transcript context) transcript)
       (try
         (let [out (StringWriter.)
               err (StringWriter.)
               outcome
               (try
                 (let [{:keys [value origin]}
                       (sci/binding [sci/out out sci/err err]
                         ;; Realized here, not at description time: forcing
                         ;; runs the sequence's own computation, which belongs
                         ;; inside the out/err capture and before the success
                         ;; event.
                         (value/realize
                          (sci/eval-string* (:sci-context context) source)))]
                   {:status :ok
                    :value {:value (value/describe value origin)
                            :out (str out) :err (str err)}})
                 (catch Throwable error {:status :error :error error}))
               ;; A transcript failure supersedes whatever the form did,
               ;; including a form that failed for its own reasons: an
               ;; evaluation that did not account for its operations has not
               ;; reconstructed anything, whatever status it reached.  A
               ;; refusal already raised at an operation is kept as it is,
               ;; because it says exactly where the divergence was and the
               ;; count that follows would only say that there was one.
               outcome (if (and (= :error (:status outcome))
                                (transcript-failure (:error outcome)))
                         outcome
                         (try
                           (verify-transcript-consumed! transcript)
                           outcome
                           (catch Throwable error
                             {:status :error :error error})))]
           (if (= :ok (:status outcome))
             (do (emit! (:runtime context) (:coordinate context)
                        (:instance-id context) :context/evaluated {:status :ok})
                 (:value outcome))
             (do (emit! (:runtime context) (:coordinate context)
                        (:instance-id context) :context/evaluation-failed
                        {:status :error
                         :error/type (.getName (class (:error outcome)))})
                 ;; Unwrapped deliberately.  A transcript refusal is addressed
                 ;; to recovery, not to the bounded context, and burying it
                 ;; under SCI's location data would make the caller dig for
                 ;; the one thing it has to act on.
                 (throw (or (transcript-failure (:error outcome))
                            (:error outcome))))))
         (finally
           (reset! (:transcript context) nil)))))))

(defn invoke [context operation-id args]
  (let [context (resolve-handle context-handles :context context)]
    (when-not (vector? args)
      (fail! "Semantic operation arguments must be a vector" {:args args}))
    (value/describe
     (invoke-authorized (:runtime context) (:effective context)
                        (:coordinate context) (:instance-id context)
                        @(:transcript context) operation-id args))))

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
