(ns datalevin.docs.handlers.pages-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datalevin.docs.handlers.pages :as pages]
            [ring.middleware.anti-forgery :as anti-forgery]))

(deftest docs-index-html-is-cached-and-invalidated
  (pages/clear-all-caches!)
  (let [calls (atom 0)]
    (with-redefs [pages/load-chapter-meta
                  (fn []
                    (swap! calls inc)
                    [{:slug "01-why-datalevin"
                      :title "Why Datalevin"
                      :chapter 1
                      :part "I"}])]
      (testing "docs index HTML is cached after first render"
        (is (= (#'pages/load-docs-index-html)
               (#'pages/load-docs-index-html)))
        (is (= 1 @calls)))

      (testing "clearing page caches invalidates the docs index HTML cache"
        (pages/clear-all-caches!)
        (#'pages/load-docs-index-html)
        (is (= 2 @calls))))))

(deftest warm-static-caches-preloads-metadata-and-docs-index
  (let [chapter-loads (atom 0)
        index-loads (atom 0)]
    (with-redefs [pages/load-chapter-meta
                  (fn []
                    (swap! chapter-loads inc)
                    [{:slug "01-why-datalevin"
                      :title "Why Datalevin"
                      :chapter 1
                      :part "I"}])
                  pages/load-docs-index-html
                  (fn []
                    (swap! index-loads inc)
                    "<div>cached</div>")]
      (pages/warm-static-caches!)
      (is (= 1 @chapter-loads))
      (is (= 1 @index-loads)))))

(deftest docs-index-renders-cached-html-inside-page-shell
  (binding [anti-forgery/*anti-forgery-token* (delay "test-token")]
    (with-redefs [pages/load-docs-index-html
                  (fn []
                    "<div id=\"toc-marker\">Table of contents</div>")]
      (let [resp (pages/docs-index {:session {}})
            body (:body resp)
            marker "<div id=\"toc-marker\">Table of contents</div>"
            marker-pos (.indexOf body marker)
            body-close-pos (.indexOf body "</body>")
            html-close-pos (.indexOf body "</html>")]
        (is (= 200 (:status resp)))
        (is (str/ends-with? body "</html>"))
        (is (<= 0 marker-pos))
        (is (< marker-pos body-close-pos))
        (is (< marker-pos html-close-pos))))))

(deftest doc-cache-uses-true-lru-eviction
  (pages/clear-all-caches!)
  (with-redefs [datalevin.docs.handlers.pages/max-doc-cache-size 4]
    (#'pages/cache-doc! "a" {:title "A"})
    (#'pages/cache-doc! "b" {:title "B"})
    (#'pages/cache-doc! "c" {:title "C"})
    (#'pages/cache-doc! "d" {:title "D"})
    (#'pages/get-cached-doc! "a")
    (#'pages/cache-doc! "e" {:title "E"})
    (let [cache @@#'datalevin.docs.handlers.pages/doc-cache]
      (is (= #{"a" "d" "e"}
             (-> cache :docs keys set)))
      (is (contains? (:docs cache) "a"))
      (is (contains? (:docs cache) "d"))
      (is (contains? (:docs cache) "e"))
      (is (not (contains? (:docs cache) "b")))
      (is (not (contains? (:docs cache) "c"))))))
