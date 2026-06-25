(ns reader.eval.queue-test
  "The Workbench queue against a real embedded Postgres: failures sort first,
   progress counts are right, and labeling a case drains it from the queue."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.domain.tags :as tags]
            [reader.eval.labels :as labels]
            [reader.eval.queue :as queue]
            [reader.eval.test-support :refer [with-eval-system]]
            [reader.ingest.tag-events :as tag-events]))

(defn- article! [ds title slug url]
  (:articles/id (crud/create! ds :articles {:title title :slug slug :canonical-url url})))

(defn- tagged! [ds aid outcome & labels]
  (when (seq labels)
    (let [ids (for [l labels] (:tags/id (crud/create! ds :tags {:slug l :label l})))]
      (tags/set-baseline! ds "article" aid (for [id ids] {:tag-id id}))))
  (tag-events/record! ds {:readable-type "article" :readable-id aid :outcome outcome
                          :model "gpt-4o-mini" :tag-count (count labels) :duration-ms 100
                          :provenance {}}))

(deftest tagging-queue-test
  (with-eval-system [system]
    (let [ds   (:reader.db/datasource system)
          good (article! ds "Good one" "good" "https://x/good")
          bad  (article! ds "Bad one"  "bad"  "https://x/bad")]
      (tagged! ds good :done "nlp" "ml")
      (tagged! ds bad  :failed)
      (testing "progress counts the readables with attempts"
        (let [{:keys [total labeled failed]} (queue/tagging-progress ds)]
          (is (= 2 total))
          (is (= 0 labeled))
          (is (= 1 failed) "the failed attempt is queued as failed")))
      (testing "the failed case is served first, with its content + flaggable tags"
        (let [c (queue/tagging-next ds)]
          (is (= bad (:readable-id c)))
          (is (= "Bad one" (:title c)))
          (is (= 1 (:position c)))
          (is (= 2 (:total c)))))
      (testing "labeling the failed case drains it; the next case is served"
        (labels/record-tagging! ds {:readable-type "article" :readable-id bad
                                    :golden #{} :labeled-by "op"})
        (let [c (queue/tagging-next ds)]
          (is (= good (:readable-id c)))
          (is (= #{"ml" "nlp"} (set (map :slug (:tags c)))) "assigned tags carry slugs to flag")
          (is (= 2 (:position c)))))
      (testing "an empty queue returns nil"
        (labels/record-tagging! ds {:readable-type "article" :readable-id good
                                    :golden #{"ml" "nlp"} :labeled-by "op"})
        (is (nil? (queue/tagging-next ds)))
        (is (= 2 (:labeled (queue/tagging-progress ds))))))))

(deftest tagging-low-confidence-priority-test
  (with-eval-system [system]
    (let [ds (:reader.db/datasource system)
          hi (article! ds "High conf" "hi" "https://x/hi")
          lo (article! ds "Low conf"  "lo" "https://x/lo")
          t1 (:tags/id (crud/create! ds :tags {:slug "a" :label "a"}))
          t2 (:tags/id (crud/create! ds :tags {:slug "b" :label "b"}))]
      ;; both done (no failures) — only the average baseline confidence differs.
      (tags/set-baseline! ds "article" hi [{:tag-id t1 :confidence 0.95}])
      (tags/set-baseline! ds "article" lo [{:tag-id t2 :confidence 0.30}])
      (tag-events/record! ds {:readable-type "article" :readable-id hi :outcome :done
                              :tag-count 1 :duration-ms 1 :provenance {}})
      (tag-events/record! ds {:readable-type "article" :readable-id lo :outcome :done
                              :tag-count 1 :duration-ms 1 :provenance {}})
      (testing "the low-confidence case is counted and served before the confident one"
        (is (= 1 (:low-conf (queue/tagging-progress ds))))
        (is (= lo (:readable-id (queue/tagging-next ds))))))))
