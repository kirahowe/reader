(ns reader.log
  "Telemere is the logging backend. The app and its libraries log through
   `clojure.tools.logging` (routed here) or SLF4J (routed by the
   `telemere-slf4j` provider on the classpath).

   `configure!` applies per-namespace min-levels (per-env EDN) and runs at
   prep time, before the system starts — libraries log at component-init, so
   the levels must be set first. The `:reader.log/publisher` component then
   swaps Telemere's default handler for a single console handler — human-
   readable in dev, machine-readable JSON in prod."
  (:require [charred.api :as json]
            [integrant.core :as ig]
            [taoensso.telemere :as tel]
            [taoensso.telemere.tools-logging :as tel-tools-logging]))

(def ^:private handler-id ::console)

(defn configure!
  "Apply per-namespace min-levels from the merged system `config` so chatty
   libraries (embedded Postgres, HikariCP) don't drown out app logs. Called
   before `ig/init`, since those loggers fire at component-init. Levels live
   under `:reader.log/publisher` `:min-levels` (per-env EDN) and are absent in
   prod, where library logs flow at their own levels."
  [config]
  (doseq [[ns-pattern level] (get-in config [::publisher :min-levels])]
    (tel/set-min-level! nil ns-pattern level)))

(defmethod ig/init-key :reader.log/publisher [_ {:keys [pretty?]}]
  (tel-tools-logging/tools-logging->telemere!)
  (tel/remove-handler! :default/console)
  (tel/add-handler! handler-id
                    (tel/handler:console
                     {:output-fn (if pretty?
                                   (tel/format-signal-fn)
                                   (tel/pr-signal-fn {:pr-fn json/write-json-str}))}))
  handler-id)

(defmethod ig/halt-key! :reader.log/publisher [_ _]
  (tel/remove-handler! handler-id))
