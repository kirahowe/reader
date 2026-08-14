(ns reader.reprocess-newsletters
  "One-shot operator entrypoint that queues a bounded batch of stale newsletter
   issues for versioned re-extraction. The normal app worker performs the actual
   R2 reads and transactional updates; this command only schedules explicit work."
  (:require [integrant.core :as ig]
            [reader.ingest :as ingest]
            [reader.main :as main])
  (:gen-class))

(defn parse-limit [s]
  (let [n (if (nil? s) 100 (parse-long s))]
    (when-not (and n (<= 1 n 1000))
      (throw (ex-info "batch limit must be an integer from 1 to 1000"
                      {:limit s :error-class :invalid-argument})))
    n))

(defn -main [& [profile limit]]
  (when-not profile
    (throw (ex-info "usage: reader.reprocess_newsletters <profile.edn> [batch-limit]"
                    {:error-class :invalid-argument})))
  (let [config (main/prep-config (concat main/core-profiles [profile]))
        system (ig/init config [:reader.log/publisher :reader.db/migrator])]
    (try
      (let [ids (ingest/enqueue-newsletter-reprocessing!
                 (:reader.db/migrator system) {:limit (parse-limit limit)})]
        (println (str "Queued " (count ids) " stale newsletter(s) for reprocessing.")))
      (finally
        (ig/halt! system)
        (shutdown-agents)))))
