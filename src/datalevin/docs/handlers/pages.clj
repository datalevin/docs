(ns datalevin.docs.handlers.pages
  (:require [datalevin.docs.views.layout :as layout]
            [clojure.java.io :as jio]
            [clj-yaml.core :as yaml]
            [datalevin.core :as d])
  (:import [org.commonmark.parser Parser]
           [org.commonmark.renderer.html HtmlRenderer]
           [org.commonmark.ext.footnotes FootnotesExtension]
           [org.commonmark.ext.gfm.tables TablesExtension]))

(def docs-dir "resources/docs")
(def extensions [(FootnotesExtension/create) (TablesExtension/create)])
(def parser* (.. Parser builder (extensions extensions) build))
(def renderer* (.. HtmlRenderer builder (extensions extensions) build))

(defn parse-frontmatter [content]
  (let [yaml-re #"^---\n([\s\S]*?)\n---\n([\s\S]*)$"
        matches (re-find yaml-re content)]
    (if matches
      {:frontmatter (yaml/parse-string (nth matches 1))
       :markdown (nth matches 2)}
      {:frontmatter {} :markdown content})))

(defn parse-markdown [markdown]
  (let [node (.parse parser* markdown)]
    (.render renderer* node)))

(defn load-doc [filename]
  (let [path (str docs-dir "/" filename ".md")
        file (jio/file path)]
    (when (.exists file)
      (let [content (slurp path)
            {:keys [frontmatter markdown]} (parse-frontmatter content)
            html (parse-markdown markdown)]
        {:title (:title frontmatter)
         :chapter (:chapter frontmatter)
         :part (:part frontmatter)
         :html html}))))

(defn load-chapter-meta
  "Reads frontmatter from all chapter .md files in docs-dir.
   Returns a sorted seq of {:slug, :title, :chapter, :part}."
  []
  (let [dir (jio/file docs-dir)
        files (.listFiles dir)
        chapters (for [f files
                       :when (.isFile f)
                       :let [name (.getName f)]
                       :when (and (.endsWith name ".md")
                                  (not= name "toc.md"))
                       :let [slug (subs name 0 (- (count name) 3))
                             content (slurp f)
                             {:keys [frontmatter]} (parse-frontmatter content)]
                       :when (:chapter frontmatter)]
                   {:slug slug
                    :title (:title frontmatter)
                    :chapter (:chapter frontmatter)
                    :part (:part frontmatter)})]
    (sort-by :chapter chapters)))

(defn home [req]
  (let [hero "<div><h1 style=\"font-size: 3rem; font-weight: bold; margin-bottom: 1.5rem; text-align: center;\">Datalevin</h1><p style=\"font-size: 1.25rem; color: #666; margin-bottom: 2rem; text-align: center;\">A simple, fast, and versatile Datalog database</p><div style=\"display: flex; justify-content: center; gap: 1rem; margin-bottom: 3rem;\"><a style=\"background: #2563eb; color: white; padding: 0.75rem 1.5rem; border-radius: 0.5rem; font-weight: 500;\" href=\"/docs/02-getting-started\">Get Started</a><a style=\"border: 1px solid #d1d5db; background: white; color: #374151; padding: 0.75rem 1.5rem; border-radius: 0.5rem; font-weight: 500;\" href=\"/docs\">Read the Book</a></div></div>"
        features "<div style=\"max-width: 5xl; margin: 0 auto; padding: 2rem;\"><h2 style=\"font-size: 1.5rem; font-weight: bold; text-align: center; margin-bottom: 2rem;\">Why Datalevin?</h2><div style=\"display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 1.5rem;\"><div style=\"background: white; padding: 1.5rem; border-radius: 0.5rem; border: 1px solid #e5e7eb;\"><h3 style=\"font-weight: 600; margin-bottom: 0.5rem;\">Fast</h3><p style=\"color: #666; font-size: 0.875rem;\">Built on LMDB, one of the fastest key-value stores.</p></div><div style=\"background: white; padding: 1.5rem; border-radius: 0.5rem; border: 1px solid #e5e7eb;\"><h3 style=\"font-weight: 600; margin-bottom: 0.5rem;\">Search</h3><p style=\"color: #666; font-size: 0.875rem;\">Built-in full-text and vector search.</p></div><div style=\"background: white; padding: 1.5rem; border-radius: 0.5rem; border: 1px solid #e5e7eb;\"><h3 style=\"font-weight: 600; margin-bottom: 0.5rem;\">Datalog</h3><p style=\"color: #666; font-size: 0.875rem;\">Powerful declarative queries.</p></div></div></div>"]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (str (layout/base-with-req "Datalevin Docs" req [:div]) hero features)}))

(defn docs-index [req]
  (let [chapters (load-chapter-meta)
        grouped (partition-by :part chapters)
        toc-html (apply str
                   "<div class=\"max-w-3xl mx-auto px-4 py-8\">"
                   "<h1 class=\"text-3xl font-bold mb-8\">Datalevin: The Definitive Guide</h1>"
                   (concat
                     (for [group grouped
                           :let [part (:part (first group))]]
                       (str "<div class=\"mb-8\">"
                            (when part
                              (str "<h2 class=\"text-lg font-semibold text-gray-500 uppercase tracking-wide mb-3\">Part "
                                   part "</h2>"))
                            "<ol class=\"space-y-2 list-none pl-0\">"
                            (apply str
                              (for [{:keys [slug title chapter]} group]
                                (str "<li>"
                                     "<a href=\"/docs/" slug "\" class=\"text-blue-600 hover:text-blue-800 hover:underline\">"
                                     (when (pos? chapter)
                                       (str "<span class=\"text-gray-400 mr-2\">" chapter ".</span>"))
                                     title
                                     "</a></li>")))
                            "</ol></div>"))
                     ["</div>"]))]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (str (layout/base-with-req "Table of Contents" req [:div]) toc-html)}))

(defn load-examples [conn doc-section]
  (when conn
    (let [db (d/db conn)]
      (d/q '[:find (pull ?e [:example/id :example/title :example/code :example/output :example/description
                              {:example/author [:user/id :user/username :user/avatar-url]}])
             :in $ ?doc-section
             :where
             [?e :example/doc-section ?doc-section]
             [?e :example/removed? false]
             [?e :example/author ?author]]
           db doc-section))))

(defn find-prev-next
  "Given a slug, returns {:prev {:slug :title} :next {:slug :title}} from the chapter list."
  [slug]
  (let [chapters (load-chapter-meta)
        idx (some (fn [[i c]] (when (= slug (:slug c)) i))
                  (map-indexed vector chapters))]
    (when idx
      {:prev (when (pos? idx) (nth chapters (dec idx)))
       :next (when (< (inc idx) (count chapters)) (nth chapters (inc idx)))})))

(defn doc-page [{:keys [path-params biff.datalevin/conn session] :as req}]
  (let [chapter (:chapter path-params)
        doc (load-doc chapter)
        examples (when conn (load-examples conn chapter))
        nav (find-prev-next chapter)]
    (if doc
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (layout/doc-page (assoc doc :examples examples :slug chapter :nav nav) req)}
      {:status 404
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (layout/not-found-page)})))
