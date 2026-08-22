(ns bb4t.execution
  "The semantic notion of an authorized execution environment.

  bb4t knows that a Context may be granted the authority to run a project's
  own command somewhere that is not the host, and knows nothing about where
  that is.  A trusted host supplies the somewhere.

  The protocol is deliberately two functions wide.  An execution environment
  has to be able to say what it is, so its identity can be recorded next to
  the results it produced, and it has to be able to run one bounded command.
  Everything else -- how the workspace is built, what the resource ceilings
  are enforced by, whether there is a machine at all -- belongs to the host
  implementation and is described rather than interfaced, because bb4t
  cannot check a claim it has no vocabulary for.

  Nothing here is projected into a bounded Context.  A Context reaches an
  execution environment only through the :project/run capability, which
  carries the argument validation, the limits, and the result semantics."
  (:require [bb4t.canonical :as canonical]))

(defprotocol ExecutionEnvironment
  "A trusted host's authorized execution environment."
  (-describe [this]
    "Inert data identifying this environment.

     Enough to distinguish materially different execution semantics: what
     implements it, what version of whatever provides the isolation, what
     the workload's world looks like, and what ceilings the host enforces.
     No host paths, no secrets, no handles.")
  (-execute [this request]
    "Runs one bounded command and returns inert data.

     The request is {:project/root :argv :cwd :timeout-ms :stdout-max-bytes
     :stderr-max-bytes}, already validated.  The result carries at least
     :status, the two bounded streams with their byte counts and truncation
     flags, :duration-ms, :worker/disposition and :project/input-stable?,
     plus :exit when and only when the workload actually exited."))

(defn execution-environment?
  [value]
  (and (some? value) (satisfies? ExecutionEnvironment value)))

(defn describe
  "The environment's description and its deterministic coordinate.

  The description is canonicalized here rather than trusted.  A host that
  returned a live object, a floating point number, or anything else outside
  the inert domain fails at the moment the runtime is built, which is the
  only moment at which failing is cheap.  A coordinate computed over a value
  that cannot round-trip would name something no reader could reconstruct."
  [environment]
  (let [description (-describe environment)]
    (when-not (map? description)
      (throw (ex-info "Execution environment description must be a map"
                      {:bb4t/error :execution-environment-invalid})))
    (let [coordinate
          (try
            (canonical/coordinate :bb4t/execution-environment description)
            (catch Throwable failure
              (throw (ex-info "Execution environment description is not inert"
                              {:bb4t/error :execution-environment-invalid}
                              failure))))]
      {:description description
       :coordinate coordinate})))
