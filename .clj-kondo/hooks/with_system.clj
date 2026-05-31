(ns hooks.with-system
  (:require [clj-kondo.hooks-api :as api]))

(defn with-system
  "Rewrites (with-system [sym] body...) into (let [sym nil] body...) so
   that clj-kondo sees the binding."
  [{:keys [node]}]
  (let [[binding-vec & body] (rest (:children node))
        sym (first (:children binding-vec))]
    {:node (api/list-node
            (list*
             (api/token-node 'let)
             (api/vector-node [sym (api/token-node 'nil)])
             body))}))
