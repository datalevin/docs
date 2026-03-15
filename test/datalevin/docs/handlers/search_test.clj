(ns datalevin.docs.handlers.search-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [datalevin.docs.handlers.search :as search]
            [hiccup2.core :as h]))

(deftest build-snippet-renders-highlighted-content-without-raw-html
  (let [text "alpha <script>alert(1)</script> omega"
        pos (.indexOf text "omega")
        snippet (#'search/build-snippet text [["omega" [pos]]])
        html (str (h/html {:mode :html}
                          (into [:p] snippet)))]
    (is (str/includes? html "&lt;script&gt;alert(1)&lt;/script&gt;"))
    (is (str/includes? html "<mark class=\"search-hit\">omega</mark>"))
    (is (not (str/includes? html "<script>alert(1)</script>")))))

(deftest search-api-handler-renders_results_without_inline_handlers
  (let [malicious-title "<img src=x onerror=alert(1)>"
        snippet ["before <script>alert(1)</script> "
                 [:mark {:class "search-hit"} "match"]]
        response (with-redefs [search/search-all (fn [_ _]
                                                   [{:type :doc
                                                     :title malicious-title
                                                     :filename "01-test"
                                                     :url "/docs/01-test"
                                                     :snippet snippet}])]
                   (search/search-api-handler {:params {:q "test"}}))
        body (:body response)]
    (is (= 200 (:status response)))
    (is (str/includes? body "&lt;img src=x onerror=alert(1)&gt;"))
    (is (str/includes? body "&lt;script&gt;alert(1)&lt;/script&gt;"))
    (is (str/includes? body "<mark class=\"search-hit\">match</mark>"))
    (is (not (str/includes? body "onmouseover=")))
    (is (not (str/includes? body "onmouseout=")))))

(deftest search-trims-before-truncating-query
  (let [captured-query (atom nil)
        over-limit (apply str (repeat 205 "x"))
        raw-query (str "   " over-limit)]
    (with-redefs [datalevin.core/db (fn [_] ::db)
                  datalevin.core/q (fn [_ _ q]
                                     (reset! captured-query q)
                                     [])]
      (search/search ::conn raw-query)
      (is (= 200 (count @captured-query)))
      (is (= (apply str (repeat 200 "x"))
             @captured-query)))))
