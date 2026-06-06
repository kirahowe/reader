(ns db
  "Babashka tasks that talk to the running dev system over nREPL. The
   server is discovered via `.nrepl-port` (see `repl`), so `bb db:seed`
   lands in whatever REPL is live — `bb dev` or the editor you jacked
   in to — with no second JVM and no hardcoded port."
  (:require [clojure.string :as str]
            [repl]))

(def ^:private seed-expr
  "(do (require 'reader.dev.seed 'integrant.repl.state)
       (reader.dev.seed/seed!
         (:reader.db/datasource integrant.repl.state/system))
       :seeded)")

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

(defn- seed-eval!
  "Resolve the live nREPL from `.nrepl-port` and run the seed there.
   Throws ConnectException when no REPL is advertised — which means the
   same thing to the caller as a dead one: nothing to seed into."
  []
  (if-let [port (repl/read-port)]
    (repl/eval-expr port seed-expr)
    (throw (java.net.ConnectException. (str "no " repl/port-file)))))

(defn seed!
  "Confirm, then populate the running dev database over nREPL. Pass
   --yes/-y/--force (`bb db:seed --yes`) to skip the prompt.

   The 0-arity wires the real IO seams; the 5-arity takes them as
   arguments so the decision/orchestration can be tested without a tty,
   a socket, or `System/exit`."
  ([] (seed! *command-line-args*
             ask!
             seed-eval!
             println
             #(System/exit %)))
  ([args ask-fn eval-fn say-fn exit-fn]
   (let [forced (forced? args)
         reply  (when-not forced (ask-fn))]
     (if-not (or forced (affirmative? reply))
       (say-fn "Aborted. Nothing was changed.")
       (do
         (say-fn "Seeding via the running nREPL ...")
         (try
           (let [{:keys [value err]} (eval-fn)]
             (if err
               (do (say-fn (str "Error: " err)) (exit-fn 1))
               (say-fn (str "Result: " value))))
           (catch java.net.ConnectException _
             (say-fn "Could not connect to a running nREPL.")
             (say-fn "Is `bb dev` running, or have you jacked in and run (go)?")
             (exit-fn 1))))))))
