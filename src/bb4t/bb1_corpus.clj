(ns bb4t.bb1-corpus
  (:require [bb4t.canonical :as canonical]
            [bb4t.context :as context]
            [bb4t.events :as events]
            [bb4t.operation :as operation]
            [bb4t.runtime :as runtime]
            [clojure.string :as str])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(def profiles
  [:agent/minimal :transform/pure :agent/project-read])

(def authority-cases
  [{:case/id :core/arithmetic
    :source "(+ 1 2)"
    :expected {:agent/minimal :allow
               :transform/pure :allow
               :agent/project-read :allow}}
   {:case/id :core/collections
    :source "(assoc {:a 1} :b 2)"
    :expected {:agent/minimal :allow
               :transform/pure :allow
               :agent/project-read :allow}}
   {:case/id :core/persistent-def
    :setup "(def bb1-persisted 41)"
    :source "(+ bb1-persisted 1)"
    :expected {:agent/minimal :allow
               :transform/pure :allow
               :agent/project-read :allow}}
   {:case/id :data/json
    :source "(data.json/read \"{\\\"ok\\\":true}\")"
    :expected {:agent/minimal :deny
               :transform/pure :allow
               :agent/project-read :allow}}
   {:case/id :project/read
    :source "(project/read \"test-resources/bb4t/bb1-project/hello.txt\")"
    :expected {:agent/minimal :deny
               :transform/pure :deny
               :agent/project-read :allow}}
   {:case/id :host/raw-slurp
    :source "(slurp \"deps.edn\")"
    :expected {:agent/minimal :deny
               :transform/pure :deny
               :agent/project-read :deny}}
   {:case/id :core/unlisted-inc
    :source "(inc 1)"
    :expected {:agent/minimal :deny
               :transform/pure :deny
               :agent/project-read :deny}}
   {:case/id :host/string-constructor
    :source "(String. \"not granted\")"
    :expected {:agent/minimal :deny
               :transform/pure :deny
               :agent/project-read :deny}}
   {:case/id :host/object-constructor
    :source "(Object.)"
    :expected {:agent/minimal :deny
               :transform/pure :deny
               :agent/project-read :deny}}
   {:case/id :host/new-syntax
    :source "(new String \"not granted\")"
    :expected {:agent/minimal :deny
               :transform/pure :deny
               :agent/project-read :deny}}
   {:case/id :host/direct-method
    :source "(.getClass \"not granted\")"
    :expected {:agent/minimal :deny
               :transform/pure :deny
               :agent/project-read :deny}}
   {:case/id :host/dot-method
    :source "(. \"not granted\" getClass)"
    :expected {:agent/minimal :deny
               :transform/pure :deny
               :agent/project-read :deny}}
   {:case/id :host/system-getenv
    :source "(System/getenv \"HOME\")"
    :expected {:agent/minimal :deny
               :transform/pure :deny
               :agent/project-read :deny}}
   {:case/id :host/class-for-name
    :source "(Class/forName \"java.lang.String\")"
    :expected {:agent/minimal :deny
               :transform/pure :deny
               :agent/project-read :deny}}
   {:case/id :host/runtime
    :source "(Runtime/getRuntime)"
    :expected {:agent/minimal :deny
               :transform/pure :deny
               :agent/project-read :deny}}
   {:case/id :host/process
    :source "(babashka.process/process [\"true\"])"
    :expected {:agent/minimal :deny
               :transform/pure :deny
               :agent/project-read :deny}}
   {:case/id :host/context-mutation
    :source "bb4t.context/create"
    :expected {:agent/minimal :deny
               :transform/pure :deny
               :agent/project-read :deny}}])

(defn- denied-error? [error]
  (let [data (ex-data error)
        message (ex-message error)]
    (or (= :unauthorized (:bb4t/error data))
        (and (= :sci/error (:type data))
             (boolean
              (re-find #"(?i)(not allowed|could not resolve|unable to resolve)"
                       message))))))

(defn- run-case [context profile {:keys [case/id source setup expected]}]
  (try
    (when setup (context/evaluate context setup))
    (let [{:keys [value]} (context/evaluate context source)
          actual :allow]
      {:case/id id
       :profile profile
       :expected (get expected profile)
       :actual actual
       :pass? (= actual (get expected profile))
       :value/check
       (case id
         :core/arithmetic (= 3 value)
         :core/collections (= {:a 1 :b 2} value)
         :core/persistent-def (= 42 value)
         :data/json (= {"ok" true} value)
         :project/read (= "hello BB1\n" value)
         true)})
    (catch Throwable error
      (let [actual (if (denied-error? error) :deny :error)]
        {:case/id id
         :profile profile
         :expected (get expected profile)
         :actual actual
         :pass? (= actual (get expected profile))
         :error/category (cond
                           (= :unauthorized (:bb4t/error (ex-data error)))
                           :bb4t/unauthorized

                           (= :sci/error (:type (ex-data error)))
                           :sci/rejected

                           :else :unexpected/error)}))))

(defn- discovery-results [project-context]
  (let [apropos (:value (context/evaluate project-context
                                          "(apropos \"project\")"))
        doc-result (context/evaluate project-context "(doc project/read)")]
    {:apropos/project-read? (boolean (some #{'project/read} apropos))
     :doc/meaningful? (and (str/includes? (:out doc-result) "project/read")
                           (str/includes? (:out doc-result)
                                          "authorized project root"))}))

(defn- canonical-results []
  (let [left {:a 1 :nested {:x #{3 2 1} :y [:ok]}}
        right (array-map :nested (array-map :y [:ok] :x (into #{} [1 3 2]))
                         :a 1)
        left-coordinate (canonical/coordinate :bb4t/test-vector left)
        right-coordinate (canonical/coordinate :bb4t/test-vector right)]
    {:order-independent? (= left-coordinate right-coordinate)
     :coordinate left-coordinate
     :different-grant?
     (not= (canonical/coordinate :bb4t/context {:grants #{:data/json-read}})
           (canonical/coordinate :bb4t/context
                                 {:grants #{:data/json-read :project/read}}))}))

(defn- validation-failure? [thunk]
  (try
    (thunk)
    false
    (catch clojure.lang.ExceptionInfo error
      (= :validation (:bb4t/error (ex-data error))))))

(defn- unauthorized-failure? [thunk]
  (try
    (thunk)
    false
    (catch clojure.lang.ExceptionInfo error
      (= :unauthorized (:bb4t/error (ex-data error))))))

(defn- any-failure? [thunk]
  (try
    (thunk)
    false
    (catch Throwable _ true)))

(defn- delete-if-present! [path]
  (when path
    (Files/deleteIfExists path)))

(defn- project-assurance [bb4t-commit]
  (let [root (Files/createTempDirectory
              "bb4t-bb1-corpus-root" (make-array FileAttribute 0))
        outside-root (Files/createTempDirectory
                      "bb4t-bb1-corpus-outside"
                      (make-array FileAttribute 0))
        file (.resolve root "file.txt")
        invalid (.resolve root "invalid.txt")
        outside (.resolve outside-root "secret.txt")
        link (.resolve root "escape.txt")]
    (try
      (Files/writeString file "1234" (make-array OpenOption 0))
      (Files/write invalid
                   (byte-array [(unchecked-byte 0xc3) (byte 0x28)])
                   (make-array OpenOption 0))
      (Files/writeString outside "secret" (make-array OpenOption 0))
      (Files/createSymbolicLink link outside (make-array FileAttribute 0))
      (let [runtime (runtime/create {:bb4t/commit bb4t-commit
                                     :resources {:project/root root}})
            context (context/create runtime
                                    {:profile :agent/project-read
                                     :limits {:project/read-max-bytes 4}})
            normal-read? (= "1234" (operation/invoke context :project/read
                                                       ["file.txt"]))
            lexical-escape? (validation-failure?
                             #(operation/invoke context :project/read
                                                ["../outside.txt"]))
            absolute-escape? (validation-failure?
                              #(operation/invoke context :project/read
                                                 [(str outside)]))
            directory? (validation-failure?
                        #(operation/invoke context :project/read ["."]))
            symlink-escape? (any-failure?
                             #(operation/invoke context :project/read
                                                ["escape.txt"]))
            invalid-utf8? (any-failure?
                           #(operation/invoke context :project/read
                                              ["invalid.txt"]))]
        (Files/writeString file "12345" (make-array OpenOption 0))
        {:project/normal-read? normal-read?
         :project/lexical-escape-denied? lexical-escape?
         :project/absolute-path-denied? absolute-escape?
         :project/directory-denied? directory?
         :project/symlink-escape-denied? symlink-escape?
         :project/invalid-utf8-denied? invalid-utf8?
         :project/size-cap-enforced?
         (validation-failure?
          #(operation/invoke context :project/read ["file.txt"]))})
      (finally
        (delete-if-present! link)
        (delete-if-present! file)
        (delete-if-present! invalid)
        (delete-if-present! outside)
        (delete-if-present! root)
        (delete-if-present! outside-root)))))

(defn- assurance-results [bb4t-commit project-root runtime contexts]
  (let [minimal (get contexts :agent/minimal)
        pure (get contexts :transform/pure)
        event-runtime (runtime/create {:bb4t/commit bb4t-commit
                                       :event-limit 3})
        event-context (context/create event-runtime {:profile :transform/pure})]
    (context/evaluate event-context "(+ 1 2)")
    (context/evaluate event-context "(data.json/read \"{}\")")
    (let [{:keys [events events/dropped]} (events/snapshot event-runtime)]
      (merge
       {:spec/unknown-profile-fails?
        (validation-failure?
         #(context/create runtime {:profile :unknown/profile}))
        :spec/unknown-key-fails?
        (validation-failure?
         #(context/create runtime {:profile :agent/minimal :unknown true}))
        :spec/profile-widening-fails?
        (validation-failure?
         #(context/create runtime
                          {:profile :agent/minimal
                           :requested-capabilities #{:project/read}
                           :authorized-capabilities #{:project/read}}))
        :spec/missing-resource-fails?
        (validation-failure?
         #(context/create (runtime/create {:bb4t/commit bb4t-commit})
                          {:profile :agent/project-read}))
        :runtime/unknown-resource-fails?
        (validation-failure?
         #(runtime/create {:bb4t/commit bb4t-commit
                           :resources {:attacker/root project-root}}))
        :runtime/manifest-inert?
        (try
          (canonical/coordinate :bb4t/test-vector (runtime/describe runtime))
          true
          (catch Throwable _ false))
        :runtime/authority-policy-recorded?
        (let [base (get-in (runtime/describe runtime)
                           [:runtime/manifest :sci/base])]
          (and (set? (:allow base))
               (set? (:default-interop-deny base))
               (map? (:default-class-overrides base))))
        :operation/direct-dispatch-denied?
        (unauthorized-failure?
         #(operation/invoke minimal :project/read ["deps.edn"]))
        :json/trailing-input-denied?
        (any-failure?
         #(operation/invoke pure :data.json/read ["{} trailing"]))
        :json/lazy-value-denied?
        (validation-failure?
         #(operation/invoke pure :data.json/write [(map identity [1 2])]))
        :events/bounded? (and (= 3 (count events)) (pos? dropped))
        :events/structured?
        (every? #(and (integer? (:event/seq %))
                      (keyword? (:event/type %))
                      (string? (:runtime/coordinate %))
                      (map? (:data %))
                      (not (contains? (:data %) :args))
                      (not (contains? (:data %) :result)))
                events)}
       (project-assurance bb4t-commit)))))

(defn run-corpus
  "Runs the shared JVM/native BB1 authority corpus and returns inert evidence."
  [bb4t-commit project-root]
  (let [runtime (runtime/create {:bb4t/commit bb4t-commit
                                 :resources {:project/root project-root}})
        contexts (into {} (map (fn [profile]
                                [profile (context/create runtime {:profile profile})]))
                       profiles)
        case-results (vec (for [test-case authority-cases
                                profile profiles]
                            (run-case (get contexts profile) profile test-case)))
        host-denied?
        (try
          (operation/invoke (get contexts :agent/minimal)
                            :project/read
                            ["test-resources/bb4t/bb1-project/hello.txt"])
          false
          (catch clojure.lang.ExceptionInfo error
            (= :unauthorized (:bb4t/error (ex-data error)))))
        equivalent (context/create runtime {:profile :agent/minimal})
        independent-state?
        (try
          (context/evaluate equivalent "bb1-persisted")
          false
          (catch Throwable _ true))
        context-descriptions (into {} (map (fn [[profile context]]
                                             [profile (context/describe context)]))
                                   contexts)
        authority-pass-count (count (filter :pass? case-results))
        value-pass-count (count (filter #(not= false (:value/check %)) case-results))
        event-types (frequencies (map :event/type (:events (events/snapshot runtime))))
        discovery (discovery-results (get contexts :agent/project-read))
        canonicalization (canonical-results)
        assurance (assurance-results bb4t-commit project-root runtime contexts)
        pass? (and (= (count case-results) authority-pass-count value-pass-count)
                   host-denied?
                   independent-state?
                   (every? true? (vals discovery))
                   (every? true? (vals (dissoc canonicalization :coordinate)))
                   (every? true? (vals assurance)))
        parity {:runtime/coordinate
                (get-in (runtime/describe runtime) [:runtime/coordinate])
                :catalog/coordinate
                (get-in (runtime/catalog runtime) [:catalog/coordinate])
                :context/coordinates
                (into {} (map (fn [[profile description]]
                                [profile (:context/coordinate description)]))
                      context-descriptions)
                :authority/results
                (mapv #(select-keys % [:case/id :profile :expected :actual
                                       :pass? :value/check :error/category])
                      case-results)
                :authority/case-count (count case-results)
                :authority/pass-count authority-pass-count
                :authority/value-pass-count value-pass-count
                :authority/host-dispatch-rechecks-grant? host-denied?
                :authority/context-state-independent? independent-state?
                :discovery discovery
                :canonicalization canonicalization
                :assurance assurance
                :pass? pass?}]
    {:corpus/version 1
     :runtime (runtime/describe runtime)
     :catalog (runtime/catalog runtime)
     :contexts context-descriptions
     :authority/results case-results
     :authority/case-count (count case-results)
     :authority/pass-count authority-pass-count
     :authority/value-pass-count value-pass-count
     :authority/host-dispatch-rechecks-grant? host-denied?
     :authority/context-state-independent? independent-state?
     :discovery discovery
     :canonicalization canonicalization
     :assurance assurance
     :events/types event-types
     :parity parity
     :pass? pass?}))

(defn- nearest-rank [sorted-values percentile]
  (nth sorted-values (dec (int (Math/ceil (* percentile
                                             (count sorted-values)))))))

(defn- latency-summary [samples]
  (let [sorted-values (vec (sort samples))]
    {:samples (count sorted-values)
     :min-ns (first sorted-values)
     :median-ns (nearest-rank sorted-values 0.5)
     :p95-ns (nearest-rank sorted-values 0.95)
     :max-ns (peek sorted-values)}))

(defn measure-context-construction
  "Measures fresh native/JVM context construction without affecting coordinates."
  [bb4t-commit project-root]
  (let [runtime (runtime/create {:bb4t/commit bb4t-commit
                                 :resources {:project/root project-root}})]
    (doseq [profile profiles
            _ (range 5)]
      (context/create runtime {:profile profile}))
    {:measurement/version 1
     :runtime/coordinate (:runtime/coordinate (runtime/describe runtime))
     :context-construction
     (into {}
           (map (fn [profile]
                  [profile
                   (latency-summary
                    (vec (for [_ (range 30)]
                           (let [start (System/nanoTime)]
                             (context/create runtime {:profile profile})
                             (- (System/nanoTime) start)))))]))
           profiles)}))
