(ns bb4t.canonical-test
  (:require [bb4t.canonical :as canonical]
            [clojure.test :refer [deftest is testing]]))

(deftest deterministic-coordinate-test
  (let [left {:a 1 :nested {:x #{3 2 1} :y [:ok]}}
        right (array-map :nested (array-map :y [:ok]
                                            :x (into #{} [1 3 2]))
                         :a 1)
        expected "sha256:2c1e6b7c6f844c15a7f6a67b0828b0fdc6d38c4fe6d436275bc802471c776d48"]
    (is (= expected (canonical/coordinate :bb4t/test-vector left)))
    (is (= expected (canonical/coordinate :bb4t/test-vector right)))
    (is (= (canonical/coordinate :bb4t/test-vector {:n 1})
           (canonical/coordinate :bb4t/test-vector {:n 1N})))
    (is (not= (canonical/coordinate :bb4t/runtime left)
              (canonical/coordinate :bb4t/catalog left)))
    (is (not= (canonical/coordinate :bb4t/test-vector {:grant #{:a/x}})
              (canonical/coordinate :bb4t/test-vector
                                    {:grant #{:a/x :b/y}})))))

(deftest canonical-domain-rejects-live-or-ambiguous-values-test
  (doseq [value [(with-meta [:value] {:source :accidental})
                 1.0
                 1/2
                 (Object.)
                 String
                 #'canonical/coordinate
                 (fn [])
                 (atom {})
                 (map identity [1 2 3])]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (canonical/coordinate :bb4t/test-vector value))
        (str "accepted unsupported value " (some-> value class .getName)))))

(deftest canonical-kind-must-be-qualified-test
  (is (thrown? clojure.lang.ExceptionInfo
               (canonical/coordinate :context {}))))
