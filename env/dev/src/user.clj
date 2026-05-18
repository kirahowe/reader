(ns user)

(defn help []
  (println)
  (println "Welcome to the reader REPL")
  (println)
  (println "Available commands:")
  (println "  (dev)   ;; switch to the dev namespace (loads integrant.repl helpers)")
  (println "  (go)    ;; start the system")
  (println "  (halt)  ;; stop the system")
  (println "  (reset) ;; refresh code and restart the system")
  (println))

(defn dev
  "Load and switch to the 'dev' namespace."
  []
  (require 'dev)
  (help)
  (in-ns 'dev)
  :loaded)

(help)
