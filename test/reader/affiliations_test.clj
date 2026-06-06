(ns reader.affiliations-test
  "Tests for `reader.affiliations`."
  (:require [clojure.test :refer [deftest is testing]]
            [reader.affiliations :as affiliations]
            [reader.db.crud :as crud]
            [reader.test-support.setup :refer [with-system]]))

(deftest list-sorted-test
  (with-system [system]
    (let [ds (:reader.db/datasource system)]
      ;; Mixed case so the ordering proves it is case-insensitive, not the
      ;; default byte-collation that would sort "arXiv" after "The New Yorker".
      (crud/create! ds :affiliations {:name "The New Yorker" :slug "tny"   :type "magazine"})
      (crud/create! ds :affiliations {:name "arXiv"          :slug "arxiv" :type "preprint"})
      (crud/create! ds :affiliations {:name "Astral Codex Ten" :slug "act" :type "newsletter"})

      (testing "ordered case-insensitively by name"
        (is (= ["arXiv" "Astral Codex Ten" "The New Yorker"]
               (map :affiliations/name (affiliations/list-sorted ds))))))))
