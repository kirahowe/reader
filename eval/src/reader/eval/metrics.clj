(ns reader.eval.metrics
  "Aggregate health for the Overview (ADR 0006): volume + coverage per pipeline,
   the most-assigned tags, and recent failures. Read-only constant SQL over the
   reader's public tables (shaped like reader.admin); accuracy-vs-labels comes
   from reader.eval.labels and is merged in by the handler."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})
(defn- raw  [ds sql] (jdbc/execute! ds [sql] opts))
(defn- raw1 [ds sql] (first (raw ds sql)))

(defn- pct-str [num den] (str (if (pos? den) (Math/round (* 100.0 (/ (double num) den))) 0) "%"))

(defn tagging-overview
  "Volume + coverage for the tagging Overview scorecard, plus top tags + recent
   failures. (Accuracy is merged in separately from the labeled set.)"
  [ds]
  (let [{:keys [tagged vocab avg-conf attempts done assigns]}
        (raw1 ds "SELECT
                    (SELECT count(DISTINCT (readable_type, readable_id)) FROM readable_tags) tagged,
                    (SELECT count(*) FROM tags) vocab,
                    (SELECT round(avg(confidence)::numeric, 2) FROM readable_tags) avg_conf,
                    (SELECT count(*) FROM tagging_events) attempts,
                    (SELECT count(*) FROM tagging_events WHERE outcome='done') done,
                    (SELECT count(*) FROM readable_tags) assigns")
        top   (raw ds "SELECT t.label, count(*) n
                       FROM readable_tags rt JOIN tags t ON t.id = rt.tag_id
                       GROUP BY t.label ORDER BY n DESC LIMIT 6")
        fails (raw ds "SELECT error_class, readable_type, readable_id FROM tagging_events
                       WHERE outcome='failed' ORDER BY created_at DESC LIMIT 5")]
    {:tagged       (or tagged 0)
     :success-rate (pct-str (or done 0) (or attempts 0))
     :avg-tags     (if (and tagged (pos? tagged))
                     (/ (Math/round (* 10.0 (/ (double (or assigns 0)) tagged))) 10.0)
                     0)
     :vocab        (or vocab 0)
     :avg-conf     (some-> avg-conf double)
     :top-tags     (mapv #(select-keys % [:label :n]) top)
     :failures     (mapv (fn [{:keys [error-class readable-type readable-id]}]
                           {:error (or error-class "unknown")
                            :url   (str readable-type "/" readable-id)})
                         fails)}))

(defn extraction-overview
  "Volume + coverage + confidence for the extraction Overview scorecard, the
   per-field coverage (% of attempts where each field was found), and recent
   failures. (Accuracy is merged in separately from the labeled set.)"
  [ds]
  (let [{:keys [attempts done body-conf entity-conf avg-authors]}
        (raw1 ds "SELECT count(*) attempts,
                         count(*) FILTER (WHERE outcome='done') done,
                         round(avg(body_confidence)::numeric, 2) body_conf,
                         round(avg(entity_confidence)::numeric, 2) entity_conf,
                         round(avg(author_count)::numeric, 1) avg_authors
                  FROM extraction_events")
        cov   (raw1 ds "SELECT count(*) total,
                               count(title_source) title,
                               count(author_source) author,
                               count(affiliation_source) affiliation,
                               count(published_source) published
                        FROM extraction_events")
        pct   (fn [k] (if (pos? (or (:total cov) 0))
                        (Math/round (* 100.0 (/ (double (or (get cov k) 0)) (:total cov))))
                        0))
        fails (raw ds "SELECT error_class, url FROM extraction_events
                       WHERE outcome='failed' ORDER BY created_at DESC LIMIT 5")]
    {:extracted    (or done 0)
     :success-rate (pct-str (or done 0) (or attempts 0))
     :body-conf    (some-> body-conf double)
     :entity-conf  (some-> entity-conf double)
     :avg-authors  (some-> avg-authors double)
     :coverage     [{:label "title" :pct (pct :title)}
                    {:label "author" :pct (pct :author)}
                    {:label "affiliation" :pct (pct :affiliation)}
                    {:label "published" :pct (pct :published)}]
     :failures     (mapv (fn [{:keys [error-class url]}]
                           {:error (or error-class "unknown") :url url})
                         fails)}))
