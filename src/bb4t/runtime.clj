(ns bb4t.runtime
  (:require [bb4t.kernel :as kernel]))

(defn create
  "Creates a trusted live runtime. Options bind build provenance and resources."
  [opts]
  (kernel/create-runtime opts))

(defn describe
  "Returns the inert RuntimeManifest and its deterministic coordinate."
  [runtime]
  (kernel/runtime-description runtime))

(defn catalog
  "Returns the inert CapabilityCatalog and its deterministic coordinate."
  [runtime]
  (kernel/catalog-description runtime))
