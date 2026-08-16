(ns bb4t.catalog)

(def ^:private json-read
  {:capability/id :data/json-read
   :effects #{}
   :doc "Parse a bounded JSON string into inert Clojure data."
   :implementation/id :bb4t.data/json-read
   :operation
   {:operation/id :data.json/read
    :input/schema [:tuple :string]
    :output/schema :json/value
    :sci/namespace 'data.json
    :sci/var 'read
    :doc "Parse a bounded JSON string into Clojure data."
    :arglists (list ['json-string])}})

(def ^:private json-write
  {:capability/id :data/json-write
   :effects #{}
   :doc "Encode bounded inert Clojure data as JSON."
   :implementation/id :bb4t.data/json-write
   :operation
   {:operation/id :data.json/write
    :input/schema [:tuple :json/value]
    :output/schema :string
    :sci/namespace 'data.json
    :sci/var 'write
    :doc "Encode bounded Clojure data as a JSON string."
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

(def capability-catalog
  {:catalog/version 1
   :catalog/type :bb4t/capability-catalog
   :capabilities
   {(:capability/id json-read) json-read
    (:capability/id json-write) json-write
    (:capability/id project-read) project-read}})

(def profiles
  {:agent/minimal
   {:profile/id :agent/minimal
    :profile/max-capabilities #{}}

   :transform/pure
   {:profile/id :transform/pure
    :profile/max-capabilities #{:data/json-read :data/json-write}}

   :agent/project-read
   {:profile/id :agent/project-read
    :profile/max-capabilities #{:data/json-read :data/json-write :project/read}
    :profile/resources {:project :project/root}
    :profile/limits {:project/read-max-bytes 1048576}}})

(def base-allow
  '#{* + - / = apropos assoc count conj def do doc first get hash-map hash-set
     if into let let* list map println quote range reduce rest str vector})

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
