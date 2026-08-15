(ns bb4t.internal-cli
  (:require [bb4t.bb1-corpus :as corpus]
            [bb4t.runtime :as runtime]))

(defn run
  "Runs fixed host-side BB1 evidence operations without widening ordinary SCI."
  [args]
  (case (vec args)
    ["bb1-corpus"]
    (let [result (corpus/run-corpus (System/getProperty "user.dir"))]
      (prn result)
      {:exit (if (:pass? result) 0 1) :force-exit false})

    ["bb1-measure"]
    (do
      (prn (corpus/measure-context-construction
            (System/getProperty "user.dir")))
      {:exit 0 :force-exit false})

    ["manifest"]
    (do
      (prn (runtime/describe (runtime/create {})))
      {:exit 0 :force-exit false})

    (do
      (binding [*out* *err*]
        (println "Unknown bb4t internal command"))
      {:exit 2 :force-exit false})))
