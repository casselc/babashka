(ns bb4t.events
  (:require [bb4t.kernel :as kernel]))

(defn subscribe
  "Subscribes a trusted callback and returns an unsubscribe function."
  [runtime subscriber]
  (kernel/subscribe runtime subscriber))

(defn snapshot
  "Returns an immutable snapshot of the runtime's bounded event buffer."
  [runtime]
  (kernel/event-snapshot runtime))

(defn context-snapshot
  "Returns currently retained events for one live Context instance."
  [context]
  (kernel/context-event-snapshot context))
