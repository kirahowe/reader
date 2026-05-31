(ns db
  "Babashka tasks that talk to the running dev system over nREPL. Uses
   bencode (bundled with bb) directly, no client dep."
  (:require [bencode.core :as bencode]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io PushbackInputStream)
           (java.net Socket)))

(def ^:private nrepl-port 7888)

(def ^:private seed-expr
  "(do (require 'reader.dev.seed 'integrant.repl.state)
       (reader.dev.seed/seed!
         (:reader.db/datasource integrant.repl.state/system))
       :seeded)")

(defn- bytes->str [x] (if (bytes? x) (String. ^bytes x "UTF-8") x))

(defn- coerce [v]
  (cond
    (map? v)    (into {} (for [[k v] v] [(keyword (bytes->str k)) (coerce v)]))
    (vector? v) (mapv coerce v)
    :else       (bytes->str v)))

(defn- nrepl-eval
  "Send `code` to the nREPL on `port`, drain replies until status :done,
   and return `{:value … :err …}`."
  [port code]
  (with-open [sock (Socket. "127.0.0.1" ^int port)
              out  (io/output-stream sock)
              in   (PushbackInputStream. (io/input-stream sock))]
    (bencode/write-bencode out {"op"   "eval"
                                "code" code
                                "id"   (str (random-uuid))})
    (.flush out)
    (loop [acc {:value nil :err nil}]
      (let [m (coerce (bencode/read-bencode in))]
        (cond
          (some #{"done"} (:status m)) acc
          (:value m) (recur (assoc acc :value (:value m)))
          (:err m)   (recur (update acc :err (fnil str "") (:err m)))
          (:out m)   (do (print (:out m)) (flush) (recur acc))
          :else      (recur acc))))))

(def ^:private force-flags #{"--yes" "-y" "--force"})

(def ^:private confirm-prompt
  (str "This TRUNCATEs every seeded table in the dev database before "
       "reloading fixtures. Continue? [y/N] "))

(defn forced?
  "True when `args` carry a flag that skips the confirmation prompt."
  [args]
  (boolean (some force-flags args)))

(defn affirmative?
  "True when a prompt reply counts as yes. A blank or nil reply — a bare
   Enter, or EOF from non-interactive stdin — is treated as no."
  [reply]
  (contains? #{"y" "yes"} (-> reply (or "") str/trim str/lower-case)))

(defn- ask!
  "Print the confirmation prompt and read one line from stdin. Returns
   nil at EOF, which `affirmative?` reads as no."
  []
  (print confirm-prompt)
  (flush)
  (read-line))

(defn seed!
  "Confirm, then populate the running dev database over nREPL. Pass
   --yes/-y/--force (`bb db:seed --yes`) to skip the prompt.

   The 0-arity wires the real IO seams; the 5-arity takes them as
   arguments so the decision/orchestration can be tested without a tty,
   a socket, or `System/exit`."
  ([] (seed! *command-line-args*
             ask!
             #(nrepl-eval nrepl-port seed-expr)
             println
             #(System/exit %)))
  ([args ask-fn eval-fn say-fn exit-fn]
   (let [forced (forced? args)
         reply  (when-not forced (ask-fn))]
     (if-not (or forced (affirmative? reply))
       (say-fn "Aborted. Nothing was changed.")
       (do
         (say-fn (str "Seeding via nREPL on " nrepl-port " ..."))
         (try
           (let [{:keys [value err]} (eval-fn)]
             (if err
               (do (say-fn (str "Error: " err)) (exit-fn 1))
               (say-fn (str "Result: " value))))
           (catch java.net.ConnectException _
             (say-fn (str "Could not connect to nREPL on port " nrepl-port "."))
             (say-fn "Is `bb dev` running in another terminal?")
             (exit-fn 1))))))))
