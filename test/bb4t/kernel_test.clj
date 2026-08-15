(ns bb4t.kernel-test
  (:require [bb4t.canonical :as canonical]
            [bb4t.catalog :as catalog]
            [bb4t.context :as context]
            [bb4t.events :as events]
            [bb4t.kernel :as kernel]
            [bb4t.operation :as operation]
             [bb4t.runtime :as runtime]
             [bb4t.value :as value]
             [clojure.java.io :as io]
             [clojure.string :as str]
             [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files LinkOption OpenOption Path]
           [java.util UUID]))

(def project-root
  (or (System/getProperty "bb4t.test.root")
      (System/getProperty "user.dir")))

(defn test-runtime
  ([] (test-runtime {}))
  ([opts]
   (runtime/create
    (merge {:resources {:project/root project-root}}
            opts))))

(defn value-data [result]
  (get-in result [:value :value/data]))

(defn invoked-data [description]
  (:value/data description))

(deftest manifest-and-catalog-are-inert-test
  (let [runtime (test-runtime)
        runtime-description (runtime/describe runtime)
        catalog-description (runtime/catalog runtime)]
    (is (string? (canonical/coordinate :bb4t/test-vector
                                       runtime-description)))
    (is (string? (canonical/coordinate :bb4t/test-vector
                                       catalog-description)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (canonical/coordinate :bb4t/test-vector runtime)))
    (is (= "140ef9dcd770a54457a02fa29c3a2f643f4968d4"
           (get-in runtime-description
                   [:runtime/manifest :upstream/commit])))
    (is (= "64163c4560e085ffdcf47951b406f62f753b7f4c"
           (get-in runtime-description [:runtime/manifest :sci/commit])))
    (is (= (str/trim
            (slurp (io/resource "META-INF/babashka/bb4t-commit")))
           (get-in runtime-description [:runtime/manifest :bb4t/commit])))
    (is (= :babashka/upstream-baseline
           (get-in runtime-description
                   [:runtime/manifest :compiled/universe :distribution])))
    (is (= #{:cheshire}
           (get-in runtime-description
                   [:runtime/manifest :catalogued/libraries])))))

(deftest catalog-validation-fails-closed-test
  (is (thrown? clojure.lang.ExceptionInfo
               (kernel/validate-catalog catalog/capability-catalog #{})))
  (let [duplicate
        (-> catalog/capability-catalog
            (assoc-in [:capabilities :duplicate/read]
                      (-> (catalog/capability :project/read)
                          (assoc :capability/id :duplicate/read
                                 :implementation/id :bb4t.duplicate/read)
                          (assoc-in [:operation :operation/id]
                                    :duplicate/read))))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (kernel/validate-catalog
                  duplicate
                  #{:bb4t.data/json-read :bb4t.data/json-write
                    :bb4t.project/read :bb4t.duplicate/read})))))

(deftest profile-attenuation-and-coordinate-test
  (let [runtime (test-runtime)
        minimal (context/create runtime {:profile :agent/minimal})
        equivalent (context/create runtime (array-map :profile :agent/minimal))
        pure (context/create runtime {:profile :transform/pure})
        restricted
        (context/create runtime
                        {:profile :agent/project-read
                         :authorized-capabilities
                         #{:data/json-read :data/json-write :project/read}
                         :requested-capabilities
                         #{:data/json-read :data/json-write}})
        minimal-description (context/describe minimal)]
    (is (= #{} (get-in minimal-description
                       [:context/effective :context/grants])))
    (is (seq (get-in minimal-description [:context/surface :allow])))
    (is (seq (get-in minimal-description [:context/surface :deny])))
    (is (= 0 (get-in minimal-description
                      [:context/surface :projected-class-count])))
    (is (= 1 (get-in minimal-description
                     [:context/surface :base-namespace-count])))
    (is (= 2 (get-in minimal-description
                     [:context/surface :base-var-count])))
    (is (= 0 (get-in minimal-description
                     [:context/surface
                      :capability-projection-namespace-count])))
    (is (= 0 (get-in minimal-description
                     [:context/surface :capability-projection-var-count])))
    (is (= 1 (get-in minimal-description
                     [:context/surface :total-projected-namespace-count])))
    (is (= 2 (get-in minimal-description
                     [:context/surface :total-projected-var-count])))
    (is (= (:context/coordinate minimal-description)
           (:context/coordinate (context/describe equivalent))))
    (is (not= (:context/coordinate minimal-description)
              (:context/coordinate (context/describe pure))))
    (is (not= (:context/coordinate (context/describe pure))
              (:context/coordinate (context/describe restricted))))
    (context/evaluate minimal "(def private-value 1)")
    (is (thrown? Throwable
                 (context/evaluate equivalent "private-value")))
    (is (thrown? Throwable (context/evaluate minimal "(inc 1)")))
    (is (thrown? Throwable (context/evaluate minimal "(String. \"x\")")))
    (is (thrown? Throwable (context/evaluate minimal "(.getClass \"x\")")))))

(deftest authority-policy-is-part-of-runtime-and-context-coordinate-test
  (let [baseline-runtime (test-runtime)
        baseline-context (context/create baseline-runtime
                                         {:profile :agent/minimal})
        widened (with-redefs [catalog/base-allow (conj catalog/base-allow 'inc)]
                  (let [runtime (test-runtime)]
                    {:runtime (runtime/describe runtime)
                     :context (context/describe
                               (context/create runtime
                                               {:profile :agent/minimal}))}))]
    (is (not= (:runtime/coordinate (runtime/describe baseline-runtime))
              (:runtime/coordinate (:runtime widened))))
    (is (not= (:context/coordinate (context/describe baseline-context))
              (:context/coordinate (:context widened))))))

(deftest malformed-context-specs-fail-before-construction-test
  (let [runtime (test-runtime)
        runtime-without-project (runtime/create {})]
    (doseq [spec [{:profile :unknown/profile}
                  {:profile :agent/minimal :unknown/value true}
                  {:profile :agent/minimal
                   :requested-capabilities #{:project/read}
                   :authorized-capabilities #{:project/read}}
                  {:profile :transform/pure
                   :requested-capabilities #{:project/read}
                   :authorized-capabilities #{:data/json-read}}
                  {:profile :agent/project-read
                   :resource-bindings {:project :attacker/root}}
                  {:profile :agent/project-read
                   :limits {:project/read-max-bytes 1048577}}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (context/create runtime spec))
          (pr-str spec)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (context/create runtime-without-project
                                 {:profile :agent/project-read})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (runtime/create {:implementation/registry {}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (runtime/create {:bb4t/commit "forged"})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (runtime/create {:resources {:attacker/root project-root}})))))

(deftest operation-dispatch-rechecks-grants-test
  (let [runtime (test-runtime)
        minimal (context/create runtime {:profile :agent/minimal})
        project (context/create runtime {:profile :agent/project-read})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"not granted"
                          (operation/invoke minimal :project/read
                                            ["deps.edn"])))
    (let [description (operation/invoke project :project/read ["deps.edn"])]
      (is (= :inert-data (:value/kind description)))
      (is (= "java.lang.String" (:value/type description)))
      (is (pos? (:value/encoded-characters description))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (operation/invoke project :unknown/operation [])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (operation/invoke project :project/read '("deps.edn"))))))

(deftest json-capability-is-narrow-test
  (let [runtime (test-runtime)
        minimal (context/create runtime {:profile :agent/minimal})
        pure (context/create runtime {:profile :transform/pure})]
    (is (thrown? Throwable
                 (context/evaluate minimal
                                   "(data.json/read \"{\\\"x\\\":1}\")")))
    (is (= {"x" 1}
           (value-data (context/evaluate pure
                                         "(data.json/read \"{\\\"x\\\":1}\")"))))
    (is (= "{\"x\":1}"
           (value-data (context/evaluate pure
                                         "(data.json/write {\"x\" 1})"))))
    (is (thrown? Throwable
                 (context/evaluate pure
                                   "(data.json/read \"{\\\"x\\\":1} trailing\")")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (operation/invoke pure :data.json/write
                                   [(map identity [1 2 3])])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (operation/invoke pure :data.json/write [{:keyword 1}])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (operation/invoke pure :data.json/write [Double/NaN])))))

(deftest project-read-containment-and-bounds-test
  (let [root (Files/createTempDirectory "bb4t-bb1-root"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        file (.resolve root "file.txt")
        invalid (.resolve root "invalid.txt")]
    (try
      (Files/writeString file "1234" (make-array OpenOption 0))
      (Files/write invalid (byte-array [(unchecked-byte 0xc3) (byte 0x28)])
                   (make-array OpenOption 0))
      (let [runtime (runtime/create {:resources {:project/root root}})
            context (context/create runtime
                                    {:profile :agent/project-read
                                     :limits {:project/read-max-bytes 4}})]
        (is (= "1234" (invoked-data
                       (operation/invoke context :project/read ["file.txt"]))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (operation/invoke context :project/read ["../outside"])))
        (is (thrown? clojure.lang.ExceptionInfo
                     (operation/invoke context :project/read [(str file)])))
        (is (thrown? clojure.lang.ExceptionInfo
                     (operation/invoke context :project/read ["."])))
        (is (thrown? java.nio.charset.CharacterCodingException
                     (operation/invoke context :project/read ["invalid.txt"])))
        (Files/writeString file "12345" (make-array OpenOption 0))
        (is (thrown? clojure.lang.ExceptionInfo
                     (operation/invoke context :project/read ["file.txt"]))))
      (finally
        (Files/deleteIfExists file)
        (Files/deleteIfExists invalid)
        (Files/deleteIfExists root)))))

(deftest project-read-rejects-symlink-escape-test
  (let [root (Files/createTempDirectory "bb4t-bb1-root"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        outside-root (Files/createTempDirectory
                      "bb4t-bb1-outside"
                      (make-array java.nio.file.attribute.FileAttribute 0))
        outside (.resolve outside-root "secret.txt")
        link (.resolve root "escape.txt")]
    (try
      (Files/writeString outside "secret" (make-array OpenOption 0))
      (Files/createSymbolicLink link outside
                                (make-array java.nio.file.attribute.FileAttribute 0))
      (let [runtime (runtime/create {:resources {:project/root root}})
            context (context/create runtime {:profile :agent/project-read})]
        (is (thrown? clojure.lang.ExceptionInfo
                     (operation/invoke context :project/read ["escape.txt"]))))
      (finally
        (Files/deleteIfExists link)
        (Files/deleteIfExists outside)
        (Files/deleteIfExists outside-root)
        (Files/deleteIfExists root)))))

(deftest discovery-and-value-description-test
  (let [runtime (test-runtime)
        project (context/create runtime {:profile :agent/project-read})]
    (is (= ['project/read]
            (value-data (context/evaluate project "(apropos \"project\")"))))
    (let [result (context/evaluate project "(doc project/read)")]
      (is (re-find #"project/read" (:out result)))
      (is (re-find #"authorized project root" (:out result))))
    (is (= :inert-data (:value/kind (value/describe {:ok true}))))
    (is (= :opaque (:value/kind (value/describe runtime))))
    (doseq [source ["(map str [1 2])"
                    "#\"x\""
                    "#inst \"2026-01-01T00:00:00.000-00:00\""
                    "#uuid \"00000000-0000-0000-0000-000000000000\""]]
      (let [description (:value (context/evaluate project source))]
        (is (= :opaque (:value/kind description)))
        (is (string? (:value/type description)))
        (is (not (contains? description :value/data)))))))

(deftest events-are-bounded-and-sanitized-test
  (let [runtime (test-runtime {:event-limit 4})
        received (atom [])
        unsubscribe (events/subscribe runtime #(swap! received conj %))
        context (context/create runtime {:profile :transform/pure})]
    (context/evaluate context "(+ 1 2)")
    (context/evaluate context "(data.json/read \"{}\")")
    (unsubscribe)
    (context/evaluate context "(+ 2 3)")
    (let [{:keys [events events/dropped]} (events/snapshot runtime)]
      (is (= 4 (count events)))
      (is (pos? dropped))
      (is (= 4 (count @received)))
      (is (every? #(every? (set (keys %))
                           [:event/id :event/type :event/seq
                            :runtime/coordinate :timestamp :data])
                  events))
      (is (every? #(not-any? (set (keys (:data %))) [:args :result]) events))
      (is (every? #(= (:context/instance-id (context/describe context))
                      (:context/instance-id %))
                  (:events (events/context-snapshot context)))))))
