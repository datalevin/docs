(ns datalevin.docs.handlers.pages-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datalevin.docs.config :as config]
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

(deftest load-chapter-meta-reads-frontmatter-without-slurping-full-files
  (pages/clear-all-caches!)
  (let [dir (doto (java.io.File/createTempFile "pages-metadata" "")
              (.delete)
              (.mkdir))
        chapter-file (java.io.File. dir "01-sample.md")
        ignored-file (java.io.File. dir "toc.md")]
    (try
      (spit chapter-file
            (str "---\n"
                 "title: Sample Chapter\n"
                 "chapter: 1\n"
                 "part: I\n"
                 "description: Frontmatter only\n"
                 "---\n"
                 (apply str (repeat 2000 "body text "))))
      (spit ignored-file "# Table of contents")
      (with-redefs [pages/docs-dir (.getAbsolutePath dir)
                    clojure.core/slurp (fn [& _]
                                         (throw (ex-info "load-chapter-meta should not slurp chapter files" {})))]
        (let [chapters (pages/load-chapter-meta)]
          (is (= 1 (count chapters)))
          (is (= "01-sample" (:slug (first chapters))))
          (is (= "Sample Chapter" (:title (first chapters))))
          (is (= 1 (:chapter (first chapters))))
          (is (= "I" (:part (first chapters))))
          (is (= "Frontmatter only" (:description (first chapters))))))
      (finally
        (pages/clear-all-caches!)
        (doseq [file (.listFiles dir)]
          (.delete file))
        (.delete dir)))))

(deftest parse-markdown-preserves-multi-language-code-groups
  (let [html (pages/parse-markdown
              (str "<div class=\"multi-lang\">\n\n"
                   "```clojure\n"
                   "(+ 1 2)\n"
                   "```\n\n"
                   "```java\n"
                   "Math.addExact(1, 2);\n"
                   "```\n\n"
                   "```python\n"
                   "1 + 2\n"
                   "```\n\n"
                   "```javascript\n"
                   "1 + 2;\n"
                   "```\n\n"
                   "</div>\n"))]
    (is (str/includes? html "<div class=\"multi-lang\">"))
    (is (= 4 (count (re-seq #"<pre>" html))))
    (is (str/includes? html "language-clojure"))
    (is (str/includes? html "language-java"))
    (is (str/includes? html "language-python"))
    (is (str/includes? html "language-javascript"))))

(deftest parse-markdown-adds-stable-heading-anchors
  (let [html (pages/parse-markdown
              (str "# Query Basics\n\n"
                   "## Rules\n\n"
                   "## Rules\n"))]
    (is (str/includes? html "<h1 id=\"query-basics\">Query Basics</h1>"))
    (is (str/includes? html "<h2 id=\"rules\">Rules</h2>"))
    (is (str/includes? html "<h2 id=\"rules-2\">Rules</h2>"))))

(deftest search-records-extracts_sections_code_and_figures
  (let [records (pages/search-records
                 "08-datalog-fundamentals"
                 {:title "Datalog Fundamentals"
                  :chapter 8
                  :part "II"}
                 (str "# Datalog Fundamentals\n\n"
                      "Intro text about logic.\n\n"
                      "## Rules\n\n"
                      "Rules let you name reusable query logic.\n\n"
                      "```clojure\n"
                      "(d/q '[:find ?e] db)\n"
                      "```\n\n"
                      "![Rule diagram showing bindings](rules.svg)\n"))
        by-key (into {} (map (juxt :search/key identity) records))]
    (is (= :section
           (get-in by-key ["08-datalog-fundamentals#datalog-fundamentals"
                           :search/type])))
    (is (= "Rules"
           (get-in by-key ["08-datalog-fundamentals#rules" :search/title])))
    (is (str/includes?
         (get-in by-key ["08-datalog-fundamentals#rules" :search/text])
         "Rules let you name reusable query logic."))
    (is (= :example
           (get-in by-key ["08-datalog-fundamentals#rules:code-1"
                           :search/type])))
    (is (= "clojure"
           (get-in by-key ["08-datalog-fundamentals#rules:code-1"
                           :search/language])))
    (is (= :figure
           (get-in by-key ["08-datalog-fundamentals#rules:figure-1"
                           :search/type])))))

(deftest substitute-markdown-vars-expands-datalevin-version
  (with-redefs [config/datalevin-version (constantly "9.9.9")]
    (is (= "version 9.9.9"
           (pages/substitute-markdown-vars "version {{datalevin-version}}")))))

(deftest load-doc-substitutes-markdown-vars-before-rendering
  (pages/clear-all-caches!)
  (let [dir (doto (java.io.File/createTempFile "pages-doc-vars" "")
              (.delete)
              (.mkdir))
        chapter-file (java.io.File. dir "02-getting-started.md")]
    (try
      (spit chapter-file
            (str "---\n"
                 "title: Getting Started\n"
                 "chapter: 2\n"
                 "---\n"
                 "Version `{{datalevin-version}}`\n"))
      (with-redefs [pages/docs-dir (.getAbsolutePath dir)
                    config/datalevin-version (constantly "9.9.9")]
        (let [doc (pages/load-doc "02-getting-started")]
          (is (str/includes? (:html doc) "9.9.9"))
          (is (not (str/includes? (:html doc) "{{datalevin-version}}")))))
      (finally
        (pages/clear-all-caches!)
        (doseq [file (.listFiles dir)]
          (.delete file))
        (.delete dir)))))

(deftest docs-index-renders-cached-html-inside-page-shell
  (binding [anti-forgery/*anti-forgery-token* (delay "test-token")]
    (with-redefs [pages/load-docs-index-html
                  (fn []
                    "<div id=\"toc-marker\">Table of contents</div>")]
      (let [resp (pages/docs-index {:session {}
                                    :base-url "https://docs.example.com"
                                    :uri "/docs"})
            body (:body resp)
            marker "<div id=\"toc-marker\">Table of contents</div>"
            marker-pos (.indexOf body marker)
            body-close-pos (.indexOf body "</body>")
            html-close-pos (.indexOf body "</html>")]
        (is (= 200 (:status resp)))
        (is (str/ends-with? body "</html>"))
        (is (str/includes? body "<title>Table of Contents | Datalevin Docs</title>"))
        (is (str/includes? body "name=\"description\""))
        (is (str/includes? body "href=\"https://docs.example.com/docs\""))
        (is (<= 0 marker-pos))
        (is (< marker-pos body-close-pos))
        (is (< marker-pos html-close-pos))))))

(deftest home-renders-seo-meta-tags
  (binding [anti-forgery/*anti-forgery-token* (delay "test-token")]
    (let [resp (pages/home {:session {}
                            :base-url "https://docs.example.com"
                            :uri "/"})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (str/includes? body "<title>Datalevin Docs</title>"))
      (is (str/includes? body "property=\"og:title\""))
      (is (str/includes? body "href=\"https://docs.example.com/\"")))))

(deftest doc-page-prefers-canonical-docs-path
  (binding [anti-forgery/*anti-forgery-token* (delay "test-token")]
    (with-redefs [pages/load-doc (fn [_]
                                   {:title "Getting Started"
                                    :chapter 2
                                    :part "I"
                                    :description "Start here."
                                    :html "<p>Intro</p>"})
                  pages/find-prev-next (constantly nil)
                  pages/load-examples (constantly nil)]
      (let [resp (pages/doc-page {:path-params {:chapter "02-getting-started"}
                                  :biff.datalevin/conn ::conn
                                  :base-url "https://docs.example.com"
                                  :uri "/chapters/02-getting-started"
                                  :session {}})
            body (:body resp)]
        (is (= 200 (:status resp)))
        (is (str/includes? body "<title>Getting Started | Datalevin Docs</title>"))
        (is (str/includes? body "content=\"Start here.\""))
        (is (str/includes? body "href=\"https://docs.example.com/docs/02-getting-started\""))
        (is (not (str/includes? body "href=\"https://docs.example.com/chapters/02-getting-started\"")))))))

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
