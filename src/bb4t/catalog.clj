(ns bb4t.catalog)

(def ^:private json-read
  {:capability/id :data/json-read
   :effects #{}
   :doc "Parse bounded integer-only JSON into inert Clojure data."
   :implementation/id :bb4t.data/json-read
   :operation
   {:operation/id :data.json/read
    :input/schema [:tuple :string]
    :output/schema :json/value
    :sci/namespace 'data.json
    :sci/var 'read
    :doc "Parse bounded integer-only JSON into Clojure data."
    :arglists (list ['json-string])}})

(def ^:private json-write
  {:capability/id :data/json-write
   :effects #{}
   :doc "Encode bounded inert Clojure data as integer-only JSON."
   :implementation/id :bb4t.data/json-write
   :operation
   {:operation/id :data.json/write
    :input/schema [:tuple :json/value]
    :output/schema :string
    :sci/namespace 'data.json
    :sci/var 'write
    :doc "Encode bounded Clojure data as an integer-only JSON string."
    :arglists (list ['value])}})

(def ^:private project-read
  {:capability/id :project/read
   :effects #{:project/read}
   :doc "Read a bounded UTF-8 file relative to the authorized project root."
   :implementation/id :bb4t.project/read
   :operation
   {:operation/id :project/read
    :input/schema [:tuple :relative-path]
    :output/schema :string
    :sci/namespace 'project
    :sci/var 'read
    :doc "Read a UTF-8 file relative to the authorized project root."
    :arglists (list ['relative-path])}})

(def ^:private project-list
  {:capability/id :project/list
   :effects #{:project/list}
   :doc "List the immediate entries of a directory under the project root."
   :implementation/id :bb4t.project/list
   :operation
   {:operation/id :project/list
    :input/schema [:tuple :relative-path]
    :output/schema :project/directory-listing
    :sci/namespace 'project
    :sci/var 'list
    :doc (str "List entries directly under a directory relative to the "
              "authorized project root. Pass \".\" for the root itself. "
              "Returns a vector of {:name :kind} sorted by name, where :kind "
              "is :file, :directory, :symlink or :other, and files also carry "
              ":bytes. Does not recurse and does not follow symbolic links.")
    :arglists (list ['relative-path])}})

(def ^:private project-search
  {:capability/id :project/search
   :effects #{:project/search}
   :doc "Search file contents under the project root with a bounded regex."
   :implementation/id :bb4t.project/search
   :operation
   {:operation/id :project/search
    :input/schema [:or [:tuple :string] [:tuple :string :map]]
    :output/schema :project/search-matches
    :sci/namespace 'project
    :sci/var 'search
    :doc (str "Search file contents under the authorized project root for a "
              "regular expression. Returns a vector of {:path :line :text} "
              "sorted by path, bounded by the context's result and file "
              "limits. Options: {:path \"subdir\"} to search one subtree, "
              "{:include-hidden? true} to include dot-entries, which are "
              "skipped by default. Does not follow symbolic links and skips "
              "files that are not valid UTF-8.")
    :arglists (list ['pattern] ['pattern 'options])}})

(def ^:private project-stat
  {:capability/id :project/stat
   :effects #{:project/read}
   :doc "Report a project file's kind, size, and content digest."
   :implementation/id :bb4t.project/stat
   :operation
   {:operation/id :project/stat
    :input/schema [:tuple :relative-path]
    :output/schema :project/file-coordinate
    :sci/namespace 'project
    :sci/var 'stat
    :doc (str "Report {:path :kind :bytes :digest} for a file under the "
              "authorized project root, or {:path :kind :absent} when it does "
              "not exist. The :digest is the coordinate project/edit requires "
              "as its :base, so a write can state which version it believed.")
    :arglists (list ['relative-path])}})

(def ^:private project-edit
  {:capability/id :project/edit
   :effects #{:project/write}
   :doc "Replace a project file's contents, anchored to a known version."
   :implementation/id :bb4t.project/edit
   :operation
   {:operation/id :project/edit
    :input/schema [:tuple :map]
    :output/schema :project/file-coordinate
    :sci/namespace 'project
    :sci/var 'edit
    :doc (str "Replace a file's contents under the authorized project root. "
              "Takes {:path \"rel/path\" :base BASE :content \"...\"} where "
              "BASE is {:digest \"sha256:...\"} from project/stat, or :absent "
              "to create a file that must not already exist. Fails as a "
              "conflict rather than overwriting when the file changed since "
              "that digest. Returns the new {:path :bytes :digest}. Writes "
              "through a temporary and renames, so a reader never sees a "
              "partial file. Does not create directories or follow symbolic "
              "links.")
    :arglists (list ['options])}})

(def effects
  "Every effect a capability may declare, and what re-running it would do.

  A capability with no declared effects is a pure function of its arguments:
  running it again can neither observe nor change anything, so a recovery that
  re-runs it computes exactly what the original computed.  An :observation
  effect reads a world that may have changed since, so re-running it answers a
  different question than the one that was asked.  An :actuation effect changes
  that world, so re-running it is not a repeated question but a second change.

  Recovery reads this rather than a list of operation names.  A capability
  added without classifying its effect fails catalog validation instead of
  being silently re-executed against the live world."
  {:project/read {:effect/kind :observation}
   :project/list {:effect/kind :observation}
   :project/search {:effect/kind :observation}
   :project/write {:effect/kind :actuation}})

(def effect-kinds #{:observation :actuation})

(defn effect-kind
  "The declared kind of one effect, or nil when it is unclassified."
  [effect]
  (get-in effects [effect :effect/kind]))

(def capability-catalog
  {:catalog/version 1
   :catalog/type :bb4t/capability-catalog
   :capabilities
   {(:capability/id json-read) json-read
    (:capability/id json-write) json-write
    (:capability/id project-read) project-read
    (:capability/id project-list) project-list
    (:capability/id project-search) project-search
    (:capability/id project-stat) project-stat
    (:capability/id project-edit) project-edit}})

(def profiles
  {:agent/minimal
   {:profile/id :agent/minimal
    :profile/max-capabilities #{}}

   :transform/pure
   {:profile/id :transform/pure
    :profile/max-capabilities #{:data/json-read :data/json-write}}

   ;; Frozen. A0, A1 and A1.1 evidence is recorded against this exact
   ;; profile, so a new capability gets a new profile rather than widening
   ;; this one underneath the recorded coordinates.
   :agent/project-read
   {:profile/id :agent/project-read
    :profile/max-capabilities #{:data/json-read :data/json-write :project/read}
    :profile/resources {:project :project/root}
    :profile/limits {:project/read-max-bytes 1048576}}

   ;; Frozen. The A2 observing surface, accepted at bb4t-a2/bbagent-a2. It
   ;; stays read-only: write authority is a deliberate step up rather than
   ;; something a surveying profile acquires because the milestone moved on.
   ;; A capability added after A2 gets a new profile, so evidence recorded
   ;; against this one keeps describing the surface it was measured with.
   :agent/project-survey
   {:profile/id :agent/project-survey
    :profile/max-capabilities #{:data/json-read :data/json-write
                                :project/read :project/list :project/search
                                :project/stat}
    :profile/resources {:project :project/root}
    :profile/limits {:project/read-max-bytes 1048576
                     :project/list-max-entries 4096
                     :project/search-max-results 200
                     :project/search-max-files 20000}}

   ;; Frozen. The A2 writable surface, accepted at bb4t-a2/bbagent-a2, and
   ;; what a bbagent session defaults to. Execution authority is not added
   ;; here when it arrives; it gets a profile of its own, so a session
   ;; created against A2 can never widen underneath its own coordinate.
   :agent/project-develop
   {:profile/id :agent/project-develop
    :profile/max-capabilities #{:data/json-read :data/json-write
                                :project/read :project/list :project/search
                                :project/stat :project/edit}
    :profile/resources {:project :project/root}
    :profile/limits {:project/read-max-bytes 1048576
                     :project/list-max-entries 4096
                     :project/search-max-results 200
                     :project/search-max-files 20000
                     :project/write-max-bytes 1048576}}})

(def project-capabilities
  "Capabilities bound to the project resource.  Each contributes the limit
   keys its implementation enforces, so a context's limits are exactly the
   limits its grants actually use."
  {:project/read {:limits #{:project/read-max-bytes}}
   :project/list {:limits #{:project/list-max-entries}}
   ;; Search reads file contents, so it carries the read byte bound too: the
   ;; limit belongs to the effect, not to the operation that names it.
   :project/search {:limits #{:project/search-max-results
                              :project/search-max-files
                              :project/read-max-bytes}}
   :project/stat {:limits #{:project/read-max-bytes}}
   ;; Editing reads before it writes, because a conflict check is a read.
   :project/edit {:limits #{:project/write-max-bytes
                            :project/read-max-bytes}}})

(def ^:private base-allow-core
  "The pure Clojure vocabulary a bounded context may use.

  A0 shipped 26 symbols, which was enough to call an operation and print the
  result. The A2 dogfood showed the cost: the model had no `fn`, no `defn`, no
  `take`, and no `subs`, so it could neither write a helper nor look at part of
  a value, and it spent most of a turn trying to work around that. The product
  thesis is that the agent composes task-specific vocabulary out of authorized
  primitives; that is not possible without the base language.

  Every symbol here is pure: it computes over inert values and reaches nothing.
  Nothing that performs IO, resolves a Var by name, evaluates data as code,
  touches a host class, mutates state, or reads the environment belongs in this
  set. Authority still comes only from projected capability operations, and the
  authority corpus is the check on that."
  '#{;; special forms and binding
     def do fn fn* if let let* letfn quote recur
     ;; definition and threading macros
     defn defn- -> ->> as-> cond cond-> cond->> condp if-let if-not if-some
     some-> some->> when when-first when-let when-not when-some
     ;; logic and comparison
     = not= not and or < <= > >= compare
     ;; arithmetic
     * + - / abs dec inc max min mod quot rem
     even? odd? neg? pos? zero?
     ;; predicates
     boolean? char? coll? contains? empty? every? fn? integer? keyword? map?
     nil? number? seq? sequential? set? some some? string? symbol? vector?
     ;; construction
     hash-map hash-set list list* set vec vector zipmap
     ;; access and update
     assoc assoc-in dissoc get get-in update update-in merge merge-with
     select-keys keys vals find key val
     ;; sequences
     concat conj cons count distinct drop drop-last drop-while filter filterv
     first flatten frequencies group-by interleave interpose into juxt last
     map mapcat mapv nth partition partition-all peek pop range reduce
     reduce-kv remove rest reverse second seq sort sort-by split-at take
     take-last take-while
     ;; functional
     apply comp complement constantly identity partial
     ;; strings and naming
     format name namespace pr-str str subs symbol keyword
     ;; discovery and output
     apropos doc println})

(def ^:private string-vars
  "The clojure.string subset a bounded context may call. Pure text functions
   only: nothing here reads a file, resolves a name, or compiles code."
  '#{blank? capitalize ends-with? escape includes? index-of join last-index-of
     lower-case replace replace-first reverse split split-lines starts-with?
     trim trim-newline triml trimr upper-case})

(def base-allow
  "Both spellings of each string function are listed deliberately.

  SCI checks permission against the symbol as written, before alias
  resolution, so an alias alone does not make `str/join` callable. Listing
  both keeps the allow-list a literal statement of what may be written, which
  is the property an authority list should have: no spelling is permitted by
  indirection."
  (into base-allow-core
        (mapcat (fn [v]
                  [(symbol "clojure.string" (str v))
                   (symbol "str" (str v))]))
        string-vars))

(def base-ns-aliases
  "Namespace aliases available without require, which the bounded context has
   no way to establish for itself.

   An alias is a name for a namespace, not a grant: a var still has to be in
   base-allow to be called through it."
  '{str clojure.string})

(def implicit-default-deny
  '#{*ns* *read-eval* *data-readers* *default-data-reader-fn*
     *reader-resolver* *suppress-read* *unchecked-math* *warn-on-reflection*
     *assert* *clojure-version* *file* global-hierarchy unquote
     clojure.walk/macroexpand-all
     clojure.lang.IAtom clojure.lang.IAtom2 clojure.lang.IDeref clojure.lang.IFn
     clojure.lang/IAtom clojure.lang/IAtom2 clojure.lang/IDeref clojure.lang/IFn})

(def base-deny
  (into
   '#{. .. doto new
      AssertionError. Exception. ArithmeticException. String. Integer. Number.
      Double. Object.
      java.lang.AssertionError. java.lang.Exception.
      java.lang.ArithmeticException. java.lang.String. java.lang.Integer.
      java.lang.Number. java.lang.Double. java.lang.Object.}
   implicit-default-deny))

(def closed-default-classes
  (into {}
        (map (fn [class-symbol]
               [class-symbol {:class nil :closed true}]))
        '#{java.lang.AssertionError java.lang.Exception
           java.lang.IllegalArgumentException clojure.lang.Delay
           clojure.lang.ExceptionInfo clojure.lang.LineNumberingPushbackReader
           clojure.lang.LazySeq java.lang.String java.io.StringWriter
           java.io.StringReader java.lang.Integer java.lang.Number
           java.lang.Double java.lang.ArithmeticException java.lang.Object
           sci.lang.IVar sci.lang.Type sci.lang.Var}))

(defn capability [capability-id]
  (get-in capability-catalog [:capabilities capability-id]))

(defn profile [profile-id]
  (get profiles profile-id))
