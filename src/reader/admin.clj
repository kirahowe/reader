(ns reader.admin
  "Read-only aggregations over extraction_events for the eval dashboard. Every
   query is constant SQL (no user input). This is the instrument that tells us
   when and where the deterministic extraction path is failing — and, once an
   LLM abstraction lands, lets us compare its :llm-sourced coverage in the same view,
   since the rows are grouped by provenance source."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn- q [ds sql] (jdbc/execute! ds [sql] opts))
(defn- q1 [ds sql] (first (q ds sql)))

(defn overview [ds]
  (q1 ds "SELECT count(*) total,
                 count(*) FILTER (WHERE outcome='done') done,
                 count(*) FILTER (WHERE outcome='failed') failed,
                 count(*) FILTER (WHERE body_confidence < 0.5) low_confidence,
                 round(avg(body_confidence)::numeric, 2) avg_body_confidence,
                 round(avg(entity_confidence)::numeric, 2) avg_entity_confidence
          FROM extraction_events"))

(def ^:private coverage-fields
  [[:title "title_source"] [:author "author_source"]
   [:affiliation "affiliation_source"] [:published "published_source"]])

(defn coverage
  "Per field: count by provenance source (a nil source means the field was not
   found at all). The key number for deciding whether the LLM tier is worth it."
  [ds]
  (into {}
        (for [[k col] coverage-fields]
          [k (q ds (str "SELECT " col " source, count(*) n FROM extraction_events "
                        "GROUP BY " col " ORDER BY n DESC"))])))

(defn by-domain [ds]
  (q ds "SELECT domain, count(*) n,
                count(*) FILTER (WHERE outcome='done') done,
                round(avg(body_confidence)::numeric, 2) avg_confidence
         FROM extraction_events GROUP BY domain ORDER BY n DESC LIMIT 15"))

(defn errors [ds]
  (q ds "SELECT error_class, count(*) n FROM extraction_events
         WHERE outcome='failed' GROUP BY error_class ORDER BY n DESC"))

(defn latency [ds]
  (q1 ds "SELECT round((percentile_cont(0.5)  WITHIN GROUP (ORDER BY fetch_ms))::numeric)   p50_fetch_ms,
                 round((percentile_cont(0.95) WITHIN GROUP (ORDER BY fetch_ms))::numeric)   p95_fetch_ms,
                 round((percentile_cont(0.5)  WITHIN GROUP (ORDER BY extract_ms))::numeric) p50_extract_ms,
                 round((percentile_cont(0.95) WITHIN GROUP (ORDER BY extract_ms))::numeric) p95_extract_ms
          FROM extraction_events WHERE outcome='done'"))

(defn recovery
  "Failed-import URLs that now have a populated article — the deterministic path
   failed but the content got in anyway (manual add or a later retry). The
   closest thing to a real error rate without an explicit correction-label write
   path: a manual add after a failed import is an implicit negative label."
  [ds]
  (q1 ds "SELECT count(DISTINCT e.url) failed_urls,
                 count(DISTINCT e.url) FILTER (WHERE a.body_html IS NOT NULL) recovered
          FROM extraction_events e
          LEFT JOIN articles a ON a.canonical_url = e.url
          WHERE e.outcome='failed'"))

(defn recent-failures [ds]
  (q ds "SELECT url, error_class, created_at FROM extraction_events
         WHERE outcome='failed' ORDER BY created_at DESC LIMIT 10"))

(defn summary [ds]
  {:overview        (overview ds)
   :coverage        (coverage ds)
   :by-domain       (by-domain ds)
   :errors          (errors ds)
   :latency         (latency ds)
   :recovery        (recovery ds)
   :recent-failures (recent-failures ds)})
