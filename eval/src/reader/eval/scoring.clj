(ns reader.eval.scoring
  "Pure scoring of pipeline output against golden labels (ADR 0006).
   Micro-averaged: sum true/false positives and false negatives across all cases,
   then divide once. Micro-averaging weights by volume and degrades gracefully on
   empty cases — the natural fit for a small, growing labeled set."
  (:require [clojure.set :as set]))

(defn- div [num den] (if (zero? den) 0.0 (double (/ num den))))

(defn ratios
  "Precision / recall / F1 derived from already-tallied {:tp :fp :fn} counts —
   the single place the formulas live (used both by `prf` and by scoring a run
   from its stored counts)."
  [{:keys [tp fp fn]}]
  (let [precision (div tp (+ tp fp))
        recall    (div tp (+ tp fn))]
    {:precision precision
     :recall    recall
     :f1        (div (* 2 precision recall) (+ precision recall))}))

(defn prf
  "Micro-averaged precision / recall / F1 over `cases`, each
   {:golden <coll> :predicted <coll>} of comparable ids (e.g. tag slugs, author
   slugs). Returns {:precision :recall :f1 :tp :fp :fn :n}."
  [cases]
  (let [counts (reduce (fn [acc {:keys [golden predicted]}]
                         (let [g  (set golden)
                               p  (set predicted)
                               tp (count (set/intersection g p))]
                           (-> acc
                               (update :tp + tp)
                               (update :fp + (- (count p) tp))
                               (update :fn + (- (count g) tp)))))
                       {:tp 0 :fp 0 :fn 0}
                       cases)]
    (merge counts {:n (count cases)} (ratios counts))))

(defn accuracy
  "Fraction of `cases` (each {:golden x :predicted x}) whose predicted equals
   golden — for a single-valued field like the extraction source. Returns
   {:accuracy :correct :n}."
  [cases]
  (let [n       (count cases)
        correct (count (filter (fn [{:keys [golden predicted]}] (= golden predicted)) cases))]
    {:accuracy (div correct n) :correct correct :n n}))
