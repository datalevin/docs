(ns datalevin.docs.handlers.pages
  (:require [datalevin.docs.views.layout :as layout]
            [clojure.java.io :as jio]
            [clj-yaml.core :as yaml]
            [datalevin.core :as d])
  (:import [org.commonmark.parser Parser]
           [org.commonmark.renderer.html HtmlRenderer]))

(def docs-dir "resources/docs")
(def parser* (.. Parser builder build))
(def renderer* (.. HtmlRenderer builder build))

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

(defn load-toc []
  (let [path (str docs-dir "/toc.md")]
    {:content (parse-markdown (slurp path))}))

(defn home [req]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (layout/base-with-req "Datalevin Docs" req
            [:div
             [:h1 {:style "font-size: 3rem; font-weight: bold; margin-bottom: 1.5rem; text-align: center;"} "Datalevin"]
             [:p {:style "font-size: 1.25rem; color: #666; margin-bottom: 2rem; text-align: center;"} 
              "A multi-paradigm database combining key-value storage, Datalog queries, full-text search, and vector search."]
             [:div {:style "display: flex; justify-content: center; gap: 1rem; margin-bottom: 3rem;"}
              [:a {:style "background: #2563eb; color: white; padding: 0.75rem 1.5rem; border-radius: 0.5rem; font-weight: 500;" :href "/docs/01-why-datalevin"} "Get Started"]
              [:a {:style "border: 1px solid #d1d5db; background: white; color: #374151; padding: 0.75rem 1.5rem; border-radius: 0.5rem; font-weight: 500;" :href "/docs"} "Browse Docs"]]])})

(defn docs-index [req]
  (let [{:keys [content]} (load-toc)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (layout/base-with-req "Table of Contents" req
               [:div
                [:h1 "Table of Contents"]
                [:div content]])}))

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

(defn doc-page [{:keys [path-params biff.datalevin/conn session] :as req}]
  (let [chapter (:chapter path-params)
        doc (load-doc chapter)
        examples (when conn (load-examples conn chapter))]
    (if doc
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (layout/doc-page (assoc doc :examples examples) req)}
      {:status 404
       :headers {"Content-Type" "text/html"}
       :body (layout/base-with-req "Not Found" req [:div "Page not found"])})))
