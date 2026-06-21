(ns reader.domain.tags-test
  "Tests for the tag domain: embedding-based dedup, the shared baseline, per-user
   override resolution, and grouping. Pure helpers are exercised directly; the
   write paths run against embedded Postgres via `with-system`."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.db.crud :as crud]
            [reader.domain.tags :as tags]
            [reader.test-support.setup :refer [with-system]]))

;; ── pure helpers ─────────────────────────────────────────────────────────

(deftest cosine-test
  (testing "identical direction is 1.0, orthogonal 0.0, opposite -1.0"
    (is (== 1.0 (tags/cosine [1.0 0.0 0.0] [2.0 0.0 0.0])))
    (is (== 0.0 (tags/cosine [1.0 0.0] [0.0 1.0])))
    (is (== -1.0 (tags/cosine [1.0 0.0] [-1.0 0.0]))))
  (testing "an empty vector is 0.0, never a divide-by-zero"
    (is (== 0.0 (tags/cosine [] [1.0])))
    (is (== 0.0 (tags/cosine [0.0 0.0] [1.0 1.0])))))

(deftest nearest-test
  (let [vocab [{:id 1 :slug "a" :label "A" :embedding [1.0 0.0 0.0]}
               {:id 2 :slug "b" :label "B" :embedding [0.0 1.0 0.0]}
               {:id 3 :slug "c" :label "C" :embedding nil}]]
    (testing "picks the highest-scoring entry and skips embeddingless ones"
      (let [{:keys [tag score]} (tags/nearest vocab [0.9 0.1 0.0])]
        (is (= 1 (:id tag)))
        (is (> score 0.9))))
    (testing "nil when the query has no embedding"
      (is (nil? (tags/nearest vocab nil))))))

(deftest resolve-effective-test
  (let [ml   {:id 1 :slug "ml" :label "ML"}
        news {:id 2 :slug "news" :label "News"}
        mine {:id 3 :slug "mine" :label "Mine"}]
    (testing "baseline minus suppressions plus additions, sorted by label"
      (is (= [{:id 1 :slug "ml" :label "ML"} {:id 3 :slug "mine" :label "Mine"}]
             (tags/resolve-effective [ml news]
                                     [(assoc news :op "suppress")
                                      (assoc mine :op "add")]))))
    (testing "adding a tag already in the baseline does not duplicate it"
      (is (= [{:id 1 :slug "ml" :label "ML"}]
             (tags/resolve-effective [ml] [(assoc ml :op "add")]))))
    (testing "no overrides leaves the baseline intact"
      (is (= [{:id 2 :slug "news" :label "News"}]
             (tags/resolve-effective [news] []))))))

(deftest distinct-tags-test
  (let [a {:queue-item-id 1 :tags [{:slug "ml" :label "ML"} {:slug "go" :label "Go"}]}
        b {:queue-item-id 2 :tags [{:slug "ml" :label "ML"}]}
        c {:queue-item-id 3 :tags []}]
    (testing "distinct tags across items, sorted by label"
      (is (= [{:slug "go" :label "Go"} {:slug "ml" :label "ML"}]
             (tags/distinct-tags [a b c]))))))

(deftest with-tag-test
  (let [a {:queue-item-id 1 :tags [{:slug "ml" :label "ML"} {:slug "go" :label "Go"}]}
        b {:queue-item-id 2 :tags [{:slug "ml" :label "ML"}]}
        c {:queue-item-id 3 :tags []}]
    (testing "filters to items carrying the slug"
      (is (= [1] (map :queue-item-id (tags/with-tag [a b c] "go"))))
      (is (= [1 2] (map :queue-item-id (tags/with-tag [a b c] "ml")))))
    (testing "a nil slug returns everything"
      (is (= [1 2 3] (map :queue-item-id (tags/with-tag [a b c] nil)))))))

;; ── DB-backed write paths ────────────────────────────────────────────────

(defn- seed-article [ds]
  (let [aff (crud/create! ds :affiliations {:name "Aff" :slug "aff" :type "blog"})]
    (crud/create! ds :articles {:affiliation-id (:affiliations/id aff)
                                :title "T" :slug "t" :canonical-url "https://example.test/t"})))

(deftest resolve-tag!-dedup-test
  (with-system [system]
    (let [ds     (:reader.db/datasource system)
          seeded (tags/resolve-tag! ds (tags/vocabulary ds) tags/default-threshold
                                    {:label "Machine Learning" :embedding [1.0 0.0 0.0]})]
      (testing "a proposal with the same slug reuses the existing tag"
        (let [again (tags/resolve-tag! ds (tags/vocabulary ds) tags/default-threshold
                                       {:label "machine learning" :embedding [0.0 1.0 0.0]})]
          (is (= (:id seeded) (:id again)))
          (is (= 1 (count (crud/find-many ds :tags))) "no new row")))
      (testing "a near-duplicate (cosine >= threshold) folds into the existing tag"
        (let [near (tags/resolve-tag! ds (tags/vocabulary ds) tags/default-threshold
                                      {:label "ML" :embedding [0.99 0.14 0.0]})]
          (is (= (:id seeded) (:id near)) "ML folds into Machine Learning by embedding")
          (is (= 1 (count (crud/find-many ds :tags))))))
      (testing "a dissimilar proposal creates a new tag"
        (let [fresh (tags/resolve-tag! ds (tags/vocabulary ds) tags/default-threshold
                                       {:label "Cooking" :embedding [0.0 1.0 0.0]})]
          (is (not= (:id seeded) (:id fresh)))
          (is (= 2 (count (crud/find-many ds :tags)))))))))

(deftest resolve-tags!-batch-test
  (with-system [system]
    (let [ds      (:reader.db/datasource system)
          results (tags/resolve-tags! ds tags/default-threshold
                                      [{:label "Rust" :embedding [1.0 0.0 0.0]}
                                       {:label "rust" :embedding [0.0 1.0 0.0]}])]
      (testing "two proposals collapsing to the same slug yield one shared tag"
        (is (= (:id (first results)) (:id (second results))))
        (is (= 1 (count (crud/find-many ds :tags))))))))

(deftest set-baseline!-test
  (with-system [system]
    (let [ds  (:reader.db/datasource system)
          aid (:articles/id (seed-article ds))
          t1  (tags/resolve-tag! ds (tags/vocabulary ds) 0.9 {:label "A" :embedding [1.0 0.0 0.0]})
          t2  (tags/resolve-tag! ds (tags/vocabulary ds) 0.9 {:label "B" :embedding [0.0 1.0 0.0]})]
      (tags/set-baseline! ds "article" aid [{:tag-id (:id t1) :confidence 0.8}
                                            {:tag-id (:id t2) :confidence 0.7}])
      (testing "writes one baseline row per assignment"
        (is (= 2 (count (crud/find-many ds :readable-tags {:readable-id aid})))))
      (testing "re-running replaces rather than appends"
        (tags/set-baseline! ds "article" aid [{:tag-id (:id t1)}])
        (let [rows (crud/find-many ds :readable-tags {:readable-id aid})]
          (is (= 1 (count rows)))
          (is (= (:id t1) (:readable-tags/tag-id (first rows)))))))))

(deftest attach-effective-test
  (with-system [system]
    (let [ds   (:reader.db/datasource system)
          aid  (:articles/id (seed-article ds))
          user (crud/create! ds :users {:email "tagtest@example.com"})
          qi   (crud/create! ds :queue-items {:user-id       (:users/id user)
                                              :readable-type "article" :readable-id aid})
          ml   (tags/resolve-tag! ds (tags/vocabulary ds) 0.9 {:label "ML" :embedding [1.0 0.0 0.0]})
          news (tags/resolve-tag! ds (tags/vocabulary ds) 0.9 {:label "News" :embedding [0.0 1.0 0.0]})
          mine (tags/resolve-tag! ds (tags/vocabulary ds) 0.9 {:label "Mine" :embedding [0.0 0.0 1.0]})]
      (tags/set-baseline! ds "article" aid [{:tag-id (:id ml)} {:tag-id (:id news)}])
      (tags/set-override! ds (:queue-items/id qi) (:id news) "suppress")
      (tags/set-override! ds (:queue-items/id qi) (:id mine) "add")
      (let [items    [{:type :article :id aid :queue-item-id (:queue-items/id qi)}]
            [result] (tags/attach-effective ds items)]
        (testing "effective tags = baseline minus suppressed plus added"
          (is (= #{"ML" "Mine"} (set (map :label (:tags result))))))))))

(deftest find-or-create-label!-caps-length-test
  (with-system [system]
    (testing "a too-long user-entered label is capped to 60 chars"
      (let [ds  (:reader.db/datasource system)
            tag (tags/find-or-create-label! ds (apply str (repeat 200 "x")))]
        (is (= 60 (count (:label tag))))))))

(deftest add-remove-tag!-test
  (with-system [system]
    (let [ds   (:reader.db/datasource system)
          aid  (:articles/id (seed-article ds))
          user (crud/create! ds :users {:email "ov@example.com"})
          qid  (:queue-items/id (crud/create! ds :queue-items
                                              {:user-id (:users/id user)
                                               :readable-type "article" :readable-id aid}))
          ml   (tags/resolve-tag! ds (tags/vocabulary ds) 0.9 {:label "ML" :embedding [1.0 0.0 0.0]})
          mine (tags/find-or-create-label! ds "My Tag")
          eff  #(set (map :label (tags/effective-for-queue-item ds (crud/by-id ds :queue-items qid))))]
      (tags/set-baseline! ds "article" aid [{:tag-id (:id ml)}])
      (testing "a baseline tag is effective by default"
        (is (= #{"ML"} (eff))))
      (testing "removing a baseline tag suppresses it"
        (tags/remove-tag! ds qid "article" aid (:id ml))
        (is (= #{} (eff))))
      (testing "re-adding a suppressed baseline tag clears the suppression"
        (tags/add-tag! ds qid "article" aid (:id ml))
        (is (= #{"ML"} (eff))))
      (testing "adding a non-baseline tag makes it effective"
        (tags/add-tag! ds qid "article" aid (:id mine))
        (is (= #{"ML" "my tag"} (eff))))
      (testing "removing a user-added tag drops it"
        (tags/remove-tag! ds qid "article" aid (:id mine))
        (is (= #{"ML"} (eff)))))))
