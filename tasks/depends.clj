(ns depends
  "Helpers for declaring bb-task dependencies.

   Pure functions return result maps with the shape
   `{:lines [...] :exit int}` (where int is non-zero  and `:lines`
   contains a message to print) for failure or `{:exit 0}` for
   success.

   `say!` is the only side-effecting function, printing `:lines`
   (if any) and exiting when `:exit` is non-zero. A zero exit code
   flows through so dep-check tasks can chain."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]))

(defn problem
  "Build a failure report: a leading ✗ line, an optional → fix line,
   and a non-zero exit code."
  ([msg]     (problem msg nil))
  ([msg fix] {:lines (cond-> [(str "✗ " msg)]
                       fix (conj (str "  → " fix)))
              :exit  1}))

(def ok
  "The canonical success result: empty lines, exit 0."
  {:exit 0})

(defn cli-missing
  "Result map for an executable presence check."
  [exe install-url]
  (if (fs/which exe)
    ok
    (problem (str exe " is not installed")
             (str "Install: " install-url))))

(defn- match-hint
  "First :explain whose :match regex finds in `output`, or nil."
  [output hints]
  (some (fn [{:keys [match explain]}]
          (when (re-find match output) explain))
        hints))

(defn shell->report
  "Result map for a captured shell `{:exit :err}` plus a `hints`
   vector. On success returns `ok`. On failure, replays captured
   stderr (if hints were used) and appends any matching hint."
  [{:keys [exit err]} hints]
  (if (zero? exit)
    ok
    (let [hint (when hints (match-hint err hints))]
      {:lines (cond-> []
                hints (conj err)
                hint  (into ["" (str "  → " hint)]))
       :exit  exit})))

(defn say!
  "Side-effecting function: print `:lines` (if any) and exit when
  `:exit` is non-zero. A zero exit is a no-op so success results can
  flow through dependent task chains without killing the bb process."
  ([report] (say! report println #(System/exit %)))
  ([{:keys [lines exit]} print-fn exit-fn]
   (run! print-fn lines)
   (when (and exit (not (zero? exit)))
     (exit-fn exit))))

(defn require-cli!
  "Convenience: report on `cli-missing`."
  [exe install-url]
  (say! (cli-missing exe install-url)))

(defn sh!
  "Run a shell command. By default inherits stdio and propagates the
   exit code without a stack trace. An optional opts map as the first
   arg supports `:hints`, a vector of `{:match regex :explain string}`
   entries; when provided, stderr is captured, replayed on non-zero
   exit, and the first matching explanation is printed below it."
  [& args]
  (let [[opts args] (if (map? (first args))
                      [(first args) (rest args)]
                      [{} args])
        {:keys [hints print-fn exit-fn]
         :or   {print-fn println exit-fn #(System/exit %)}} opts
        proc-opts   (cond-> {:continue true}
                      hints (assoc :err :string))
        result      (apply p/shell proc-opts args)]
    (say! (shell->report result hints) print-fn exit-fn)))
