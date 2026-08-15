(ns bb4t.bb1-corpus-test
  (:require [bb4t.bb1-corpus :as corpus]
            [clojure.test :refer [deftest is]]))

(def project-root
  (or (System/getProperty "bb4t.test.root")
      (System/getProperty "user.dir")))

(deftest shared-authority-corpus-test
  (let [result (corpus/run-corpus "bb1-test" project-root)]
    (is (:pass? result))
    (is (= 51 (:authority/case-count result)))
    (is (= (:authority/case-count result)
           (:authority/pass-count result)
           (:authority/value-pass-count result)))
    (is (:authority/host-dispatch-rechecks-grant? result))
    (is (:authority/context-state-independent? result))
    (is (every? true? (vals (:discovery result))))
    (is (every? true? (vals (:assurance result))))
    (is (:order-independent? (:canonicalization result)))
    (is (:different-grant? (:canonicalization result)))))
