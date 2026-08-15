(ns bb4t.context
  (:require [bb4t.kernel :as kernel]))

(defn create
  "Creates one fresh bounded SCI Context from a trusted ContextSpec."
  [runtime context-spec]
  (kernel/create-context runtime context-spec))

(defn describe
  "Returns inert ContextSpec, effective authority, surface, and coordinates."
  [context]
  (kernel/context-description context))

(defn evaluate
  "Evaluates source and returns an inert value description plus output data."
  [context source]
  (kernel/evaluate context source))
