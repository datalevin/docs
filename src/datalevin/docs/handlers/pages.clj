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

(def ^:private card-style "dl-card")

(def ^:private btn-style "dl-btn")

(def ^:private slack-url
  (str "https://clojurians.slack.com/join/shared_invite/"
       "zt-3mkyfmlaa-IHIYCGV0hEcZomaHnCHgdQ"
       \# "/shared-invite/email"))

(defn home [req]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body
   (layout/base-with-req "Datalevin Docs" req
    ;; Hero
    [:div {:style "text-align:center;padding:3rem 1rem 0"}
     [:h1 {:style "font-size:3rem;font-weight:bold;margin-bottom:1.5rem"} "Datalevin"]
     [:p {:style "font-size:1.25rem;color:#666;margin-bottom:2rem"}
      "A simple, fast, and versatile open-source Datalog database"]
     [:div {:style "display:flex;justify-content:center;gap:1rem;margin-bottom:3rem"}
      [:a {:href "/docs/02-getting-started" :class "dl-btn-primary"} "Get Started"]
      [:a {:href "/docs" :class "dl-btn"} "Read the Book"]]]
    ;; Features
    [:div {:style "max-width:64rem;margin:0 auto;padding:2rem 2rem"}
     [:h2 {:style "font-size:1.5rem;font-weight:bold;text-align:center;margin-bottom:2rem"} "Why Datalevin?"]
     [:div {:style "display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:1.5rem"}
      [:a {:href "/docs/03-mental-model" :class card-style}
       [:h3 {:style "font-weight:600;margin-bottom:0.5rem"} "Simple"]
       [:p {:style "color:#666;font-size:0.875rem"} "Declarative Datalog query language and triple data model."]]
      [:a {:href "/docs/04-storage-fundamentals" :class card-style}
       [:h3 {:style "font-weight:600;margin-bottom:0.5rem"} "Fast"]
       [:p {:style "color:#666;font-size:0.875rem"} "Built on LMDB, one of the fastest key-value stores."]]
      [:a {:href "/docs/01-why-datalevin" :class card-style}
       [:h3 {:style "font-weight:600;margin-bottom:0.5rem"} "Versatile"]
       [:p {:style "color:#666;font-size:0.875rem"} "Relational, graph, document, vector, full-text search in a unified data store."]]]
     [:div {:style "display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:1.5rem;margin-top:1.5rem"}
      [:a {:href "/docs/02-getting-started" :class card-style}
       [:h3 {:style "font-weight:600;margin-bottom:0.5rem"} "Flexible"]
       [:p {:style "color:#666;font-size:0.875rem"} "Seamless deployment model \u2014 embedded, client/server, and command line scripting."]]
      [:a {:href "/docs/26-durability-backups" :class card-style}
       [:h3 {:style "font-weight:600;margin-bottom:0.5rem"} "Reliable"]
       [:p {:style "color:#666;font-size:0.875rem"} "Transaction log access, replication and high availability."]]
      [:a {:href "/docs/31-agent-memory" :class card-style}
       [:h3 {:style "font-weight:600;margin-bottom:0.5rem"} "AI Ready"]
       [:p {:style "color:#666;font-size:0.875rem"} "Built-in MCP server, ideal for agent memory and agentic applications."]]]]
    ;; Community
    [:div {:style "max-width:64rem;margin:0 auto;padding:2rem 2rem"}
     [:h2 {:style "font-size:1.5rem;font-weight:bold;text-align:center;margin-bottom:0.75rem"} "Community"]
     [:p {:style "text-align:center;color:#666;font-size:1rem;margin-bottom:1.5rem"}
      "Sharing ideas and helping each other with questions, examples, and issues."]
     [:div {:style "display:flex;justify-content:center;gap:1rem;flex-wrap:wrap"}
      [:a {:href "/examples" :class btn-style} "Community Examples"]
      [:a {:href slack-url :target "_blank" :rel "noopener noreferrer" :class btn-style}
       (str \# "datalevin Clojurians Slack")]
      [:a {:href "https://github.com/datalevin/datalevin/discussions"
           :target "_blank" :rel "noopener noreferrer" :class btn-style}
       "GitHub Discussions"]]]
    ;; Support
    [:div {:style "max-width:64rem;margin:0 auto;padding:2rem 2rem 3rem"}
     [:h2 {:style "font-size:1.5rem;font-weight:bold;text-align:center;margin-bottom:0.75rem"} "Support the Project"]
     [:p {:style "text-align:center;color:#666;font-size:1rem;margin-bottom:1.5rem"}
      "If you enjoy Datalevin and it is helping you succeed, consider sponsoring the development and maintenance of this project."]
     [:div {:style "display:flex;justify-content:center"}
      [:a {:href "https://github.com/sponsors/huahaiy" :target "_blank" :rel "noopener noreferrer" :class btn-style}
       "GitHub Sponsors"]]])}))

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
