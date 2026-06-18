(ns reader.papers.arxiv-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reader.papers.arxiv :as arxiv]))

(defn- ->resp
  "A delivered promise of a request! result, the shape fetch-body/fetch-metadata
   deref. `serve` maps a url -> body string (nil ⇒ a 404 with no body)."
  [serve]
  (fn [url]
    (doto (promise)
      (deliver (if-let [body (serve url)] {:status 200 :body body} {:status 404 :body nil})))))

(deftest sanitize-keeps-mathml-strips-scripts
  (let [html (str "<h1 id=\"sec1\">Title</h1>"
                  "<p>energy <math><msup><mi>e</mi><mn>2</mn></msup></math> here</p>"
                  "<p><a href=\"#sec1\">to section</a> <a href=\"fig1\">figure</a></p>"
                  "<p><mo definitionurl=\"javascript:alert(1)\">x</mo></p>"
                  "<script>alert(1)</script>")
        out  (arxiv/sanitize html "https://arxiv.org/html/2401.12345")]
    (testing "MathML survives so equations render natively"
      (is (str/includes? out "<math"))
      (is (str/includes? out "<msup"))
      (is (str/includes? out "<mi>e</mi>")))
    (testing "semantic structure + element ids survive, scripts are stripped"
      (is (str/includes? out "<h1 id=\"sec1\">Title</h1>") "ids are kept as scroll targets")
      (is (not (str/includes? out "alert")))
      (is (not (str/includes? out "<script"))))
    (testing "the URL-bearing definitionurl attribute is dropped entirely"
      (is (not (str/includes? out "definitionurl")))
      (is (not (str/includes? out "javascript"))))
    (testing "same-document anchors stay relative (in-reader scroll), resources absolutize"
      (is (str/includes? out "href=\"#sec1\"") "the #fragment is not bounced out to arxiv.org")
      (is (str/includes? out "https://arxiv.org/html/fig1") "a resource link resolves to absolute"))))

(deftest fetch-body-prefers-arxiv-then-ar5iv
  (testing "uses arXiv's native HTML when present"
    (let [out (arxiv/fetch-body (->resp (fn [url] (when (str/starts-with? url "https://arxiv.org/html")
                                                    "<p>native <math><mi>x</mi></math></p>")))
                                "2401.12345")]
      (is (str/includes? out "native"))
      (is (str/includes? out "<math"))))
  (testing "falls back to ar5iv when arXiv has no HTML"
    (let [out (arxiv/fetch-body (->resp (fn [url] (when (str/includes? url "ar5iv") "<p>ar5iv body</p>")))
                                "hep-th/9901001")]
      (is (str/includes? out "ar5iv body"))))
  (testing "nil when neither serves it"
    (is (nil? (arxiv/fetch-body (->resp (constantly nil)) "2401.12345")))))

(def ^:private arxiv-atom
  "<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry>
     <title>Attention Is All You Need</title>
     <summary>We propose the Transformer.</summary>
     <published>2017-06-12T17:57:34Z</published>
     <author><name>Ashish Vaswani</name></author>
     <author><name>Noam Shazeer</name></author>
   </entry></feed>")

(deftest fetch-metadata-parses-the-atom-feed
  (testing "title/abstract/date + author names, in the openalex graph shape"
    (let [g (arxiv/fetch-metadata (->resp (constantly arxiv-atom)) "1706.03762")]
      (is (= "Attention Is All You Need" (:title g)))
      (is (= "We propose the Transformer." (:abstract g)))
      (is (= {:name "arXiv" :type "repository"} (:venue g)))
      (is (= ["Ashish Vaswani" "Noam Shazeer"] (map :name (:authors g))))))
  (testing "nil when the feed has no entry"
    (is (nil? (arxiv/fetch-metadata (->resp (constantly nil)) "0000.00000")))))
