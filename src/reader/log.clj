(ns reader.log
  "Telemere is the logging backend. The app and its libraries log through
   `clojure.tools.logging`; this component routes those calls into Telemere
   and installs a single console handler in place of Telemere's default —
   human-readable in dev, machine-readable JSON in prod."
  (:require [integrant.core :as ig]
            [jsonista.core :as json]
            [taoensso.telemere :as tel]
            [taoensso.telemere.tools-logging :as tel-tools-logging]))

(def ^:private handler-id ::console)

(defmethod ig/init-key :reader.log/publisher [_ {:keys [pretty?]}]
  (tel-tools-logging/tools-logging->telemere!)
  (tel/remove-handler! :default/console)
  (tel/add-handler! handler-id
                    (tel/handler:console
                     {:output-fn (if pretty?
                                   (tel/format-signal-fn)
                                   (tel/pr-signal-fn {:pr-fn json/write-value-as-string}))}))
  handler-id)

(defmethod ig/halt-key! :reader.log/publisher [_ _]
  (tel/remove-handler! handler-id))
