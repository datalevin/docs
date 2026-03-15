(ns datalevin.docs.handlers.pages
  (:require [datalevin.docs.views.layout :as layout]
            [datalevin.docs.util :as util]
            [clojure.java.io :as jio]
            [clojure.string :as str]
            [clj-yaml.core :as yaml]
            [hiccup2.core :as h]
            [datalevin.core :as d])
  (:import [java.time Instant]
           [java.time.format DateTimeFormatter]
           [org.commonmark.parser Parser]
           [org.commonmark.renderer.html HtmlRenderer]
           [org.commonmark.ext.footnotes FootnotesExtension]
           [org.commonmark.ext.gfm.tables TablesExtension]))

(def docs-dir "resources/docs")
(def extensions [(FootnotesExtension/create) (TablesExtension/create)])
(def parser* (.. Parser builder (extensions extensions) build))
(def renderer* (.. HtmlRenderer builder (extensions extensions) build))

;; Cache for chapter metadata to avoid repeated file I/O
;; Metadata only changes when docs are added/removed, so we cache indefinitely
;; and rely on explicit invalidation (clear-all-caches!) when needed.
(defonce ^:private chapter-meta-cache (atom nil))

;; Cache for parsed document HTML
;; Documents rarely change in production; cleared on reindex or dev file changes
;; Capped at 100 entries to prevent unbounded memory growth
(defn- empty-doc-cache []
  {:docs {}
   :last-access {}
   :tick 0})

(defonce ^:private doc-cache (atom (empty-doc-cache)))
(def ^:private max-doc-cache-size 100)

;; Cache for rendered docs index HTML
;; Chapter structure changes rarely, so this is invalidated together with chapter metadata
(defonce ^:private docs-index-cache (atom nil))

(def ^:private description-max-length 180)

(def ^:private home-description
  "Datalevin documentation, examples, and guides for Datalog, LMDB-backed storage, search, vectors, and production deployment.")

(def ^:private docs-index-description
  "Browse the Datalevin book by chapter, from getting started and storage fundamentals to search, AI, and operations.")

(defn- clear-chapter-cache!
  "Clear the chapter metadata cache. Call when docs are updated."
  []
  (reset! chapter-meta-cache nil))

(defn- clear-doc-cache!
  "Clear the document cache. Call when docs are updated."
  []
  (reset! doc-cache (empty-doc-cache)))

(defn- clear-docs-index-cache!
  "Clear the docs index HTML cache. Call when docs are updated."
  []
  (reset! docs-index-cache nil))

(defn clear-all-caches!
  "Clear all caches. Call when docs are updated."
  []
  (clear-chapter-cache!)
  (clear-doc-cache!)
  (clear-docs-index-cache!))

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

(defn- doc-description
  [frontmatter html]
  (or (some-> (:description frontmatter) str/trim not-empty)
      (util/summarize-html html description-max-length)
      util/site-description))

(defn- mark-doc-access
  [cache filename]
  (let [tick (inc (:tick cache 0))]
    (-> cache
        (assoc :tick tick)
        (assoc-in [:last-access filename] tick))))

(defn- prune-doc-cache
  [cache]
  (if (> (count (:docs cache)) max-doc-cache-size)
    (let [keep-count (max 1 (quot (* max-doc-cache-size 3) 4))
          keep-keys (->> (:last-access cache)
                         (sort-by val >)
                         (take keep-count)
                         (map key)
                         set)]
      {:docs (select-keys (:docs cache) keep-keys)
       :last-access (select-keys (:last-access cache) keep-keys)
       :tick (:tick cache)})
    cache))

(defn- get-cached-doc!
  [filename]
  (let [cache (swap! doc-cache
                     (fn [cache]
                       (if (contains? (:docs cache) filename)
                         (mark-doc-access cache filename)
                         cache)))]
    (get-in cache [:docs filename])))

(defn- cache-doc!
  [filename doc]
  (swap! doc-cache
         (fn [cache]
           (-> cache
               (assoc-in [:docs filename] doc)
               (mark-doc-access filename)
               (prune-doc-cache))))
  doc)

(defn load-doc [filename]
  (or (get-cached-doc! filename)
      (let [path (str docs-dir "/" filename ".md")
            file (jio/file path)]
        (when (.exists file)
          (let [content (slurp path)
                {:keys [frontmatter markdown]} (parse-frontmatter content)
                html (parse-markdown markdown)
                doc {:title (:title frontmatter)
                     :chapter (:chapter frontmatter)
                     :part (:part frontmatter)
                     :description (doc-description frontmatter html)
                     :html html}]
            (cache-doc! filename doc))))))

(defn load-chapter-meta
  "Reads frontmatter from all chapter .md files in docs-dir.
   Returns a sorted seq of {:slug, :title, :chapter, :part}.
   Uses caching to avoid repeated file I/O. Cache is cleared when docs change."
  []
  (or @chapter-meta-cache
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
                        :part (:part frontmatter)
                        :description (some-> (:description frontmatter) str/trim not-empty)
                        :lastmod (.lastModified f)})
            sorted (sort-by :chapter chapters)]
        (reset! chapter-meta-cache sorted)
        sorted)))

(def ^:private card-style "dl-card")

(def ^:private btn-style "dl-btn")

(def ^:private slack-url
  (str "https://clojurians.slack.com/join/shared_invite/"
       "zt-3mkyfmlaa-IHIYCGV0hEcZomaHnCHgdQ"
       \# "/shared-invite/email"))

(defn- render-docs-index-html
  [chapters]
  (let [grouped (partition-by :part chapters)]
    (apply str
           "<div class=\"max-w-3xl mx-auto px-4 py-8\">"
           "<h1 class=\"text-3xl font-bold mb-8\" style=\"color:var(--text-primary, #e5e7eb)\"><i>Datalevin: The Definitive Guide</i></h1>"
           "<p class=\"mb-6\"><a href=\"/pdf\" class=\"dl-btn\">Download PDF</a></p>"
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
                                 "<a href=\"/docs/" slug "\" class=\"hover:underline\" style=\"color:var(--text-link, #22d3ee);\">"
                                 (when (pos? chapter)
                                   (str "<span class=\"text-gray-500 mr-2\">" chapter ".</span>"))
                                 title
                                 "</a></li>")))
                   "</ol></div>"))
            ["</div>"]))))

(defn- load-docs-index-html
  []
  (or @docs-index-cache
      (let [html (render-docs-index-html (load-chapter-meta))]
        (reset! docs-index-cache html)
        html)))

(defn warm-static-caches!
  "Preload caches derived from the docs directory so the first request
   after startup or reindex does not pay the full file I/O and parsing cost."
  []
  (load-chapter-meta)
  (load-docs-index-html))

(defn- iso-instant
  [millis]
  (.format DateTimeFormatter/ISO_INSTANT
           (Instant/ofEpochMilli millis)))

(defn robots-txt [req]
  {:status 200
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body (str "User-agent: *\n"
              "Allow: /\n"
              "Disallow: /admin/\n"
              "Disallow: /auth/\n"
              "Disallow: /api/\n"
              "Disallow: /examples/form\n"
              "Disallow: /search\n"
              "Sitemap: " (or (util/absolute-url req "/sitemap.xml")
                              "/sitemap.xml")
              "\n")})

(defn- sitemap-entry
  [req {:keys [path lastmod priority]}]
  (when-let [loc (util/absolute-url req path)]
    (str "<url>"
         "<loc>" (util/escape-html loc) "</loc>"
         (when lastmod
           (str "<lastmod>" (iso-instant lastmod) "</lastmod>"))
         (when priority
           (str "<priority>" priority "</priority>"))
         "</url>")))

(defn sitemap-xml [req]
  (let [static-urls [{:path "/" :priority "1.0"}
                     {:path "/docs" :priority "0.9"}
                     {:path "/examples" :priority "0.8"}]
        doc-urls (for [{:keys [slug lastmod]} (load-chapter-meta)]
                   {:path (str "/docs/" slug)
                    :lastmod lastmod
                    :priority "0.7"})
        xml (apply str
                   (concat ["<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">"]
                           (keep (partial sitemap-entry req)
                                 (concat static-urls doc-urls))
                           ["</urlset>"]))]
    {:status 200
     :headers {"Content-Type" "application/xml; charset=utf-8"}
     :body xml}))

(defn home [req]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body
   (layout/base-with-req "Datalevin Docs" req
                         {:description home-description
                          :canonical-path "/"}
	    ;; Hero
                         [:div {:style "text-align:center;padding:4rem 1rem 0"}
                          [:h1 {:style "font-size:3.5rem;font-weight:bold;margin-bottom:1.5rem;background:linear-gradient(135deg,#06b6d4,#3b82f6,#8b5cf6);-webkit-background-clip:text;-webkit-text-fill-color:transparent;background-clip:text;"} "Datalevin"]
                          [:p {:style "font-size:1.25rem;color:#9ca3af;margin-bottom:2rem"}
                           "SQLite for connected data and reasoning"]                          [:div {:style "display:flex;justify-content:center;gap:1rem;margin-bottom:3rem"}
                           [:a {:href "/docs/02-getting-started" :class "dl-btn-primary"} "Get Started"]
                           [:a {:href "/docs" :class "dl-btn"} "Read the Book"]]]
    ;; Features
                         [:div {:style "max-width:64rem;margin:0 auto;padding:2rem 2rem"}
                          [:h2 {:class "section-title" :style "font-size:1.5rem;font-weight:bold;text-align:center;margin-bottom:2rem;color:#f9fafb"} "Why Datalevin?"]
                          [:div {:style "display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:1.5rem"}
                           [:a {:href "/docs/03-mental-model" :class card-style}
                            [:h3 {:style "font-weight:600;margin-bottom:0.5rem;color:#f9fafb"} "Simple"]
                            [:p {:style "color:#9ca3af;font-size:0.875rem"} "Declarative Datalog query language and triple data model."]]
                           [:a {:href "/docs/04-storage-fundamentals" :class card-style}
                            [:h3 {:style "font-weight:600;margin-bottom:0.5rem;color:#f9fafb"} "Fast"]
                            [:p {:style "color:#9ca3af;font-size:0.875rem"} "Built on LMDB, one of the fastest key-value stores."]]
                           [:a {:href "/docs/01-why-datalevin" :class card-style}
                            [:h3 {:style "font-weight:600;margin-bottom:0.5rem;color:#f9fafb"} "Versatile"]
                            [:p {:style "color:#9ca3af;font-size:0.875rem"} "Relational, graph, document, vector, full-text search in a unified data store."]]]
                          [:div {:style "display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:1.5rem;margin-top:1.5rem"}
                           [:a {:href "/docs/02-getting-started" :class card-style}
                            [:h3 {:style "font-weight:600;margin-bottom:0.5rem;color:#f9fafb"} "Flexible"]
                            [:p {:style "color:#9ca3af;font-size:0.875rem"} "Seamless deployment: embedded in applications, client/server, and fast starting command line scripting."]]
                           [:a {:href "/docs/26-durability-backups" :class card-style}
                            [:h3 {:style "font-weight:600;margin-bottom:0.5rem;color:#f9fafb"} "Reliable"]
                            [:p {:style "color:#9ca3af;font-size:0.875rem"} "Robust ACID compliance, WAL writes, transaction log access, read replica and high availability."]]
                           [:a {:href "/docs/31-agent-memory" :class card-style}
                            [:h3 {:style "font-weight:600;margin-bottom:0.5rem;color:#f9fafb"} "AI Native"]                            [:p {:style "color:#9ca3af;font-size:0.875rem"} "CLI with full capability and built-in MCP server, ideal for agent memory and agentic application."]]]]    ;; Ecosystem
                         [:div {:style "max-width:64rem;margin:0 auto;padding:2rem 2rem"}
                          [:h2 {:class "section-title" :style "font-size:1.5rem;font-weight:bold;text-align:center;margin-bottom:0.75rem;color:#f9fafb"} "Ecosystem"]
                          [:p {:style "text-align:center;color:#9ca3af;font-size:1rem;margin-bottom:1.5rem"}
                           "Sharing ideas and helping each other with questions, examples, and issues."]
                          [:div {:style "display:flex;justify-content:center;gap:1rem;flex-wrap:wrap"}
                           [:a {:href "/examples" :class btn-style} "User Examples"]
                           [:a {:href slack-url :target "_blank" :rel "noopener noreferrer" :class btn-style}
                            (str \# "datalevin Clojurians Slack")]
                           [:a {:href "https://github.com/datalevin/datalevin/discussions"
                                :target "_blank" :rel "noopener noreferrer" :class btn-style}
                            "GitHub Discussions"]]]
    ;; Support
                         [:div {:style "max-width:64rem;margin:0 auto;padding:2rem 2rem 3rem"}
                          [:h2 {:class "section-title" :style "font-size:1.5rem;font-weight:bold;text-align:center;margin-bottom:0.75rem;color:#f9fafb"} "Support the Project"]
                          [:p {:style "text-align:center;color:#9ca3af;font-size:1rem;margin-bottom:1.5rem"}
                           "If you enjoy Datalevin and it is helping you succeed, consider sponsoring the development and maintenance of this project."]
                          [:div {:style "display:flex;justify-content:center"}
                           [:a {:href "https://github.com/sponsors/huahaiy" :target "_blank" :rel "noopener noreferrer" :class btn-style}
                            "GitHub Sponsors"]]])})

(defn docs-index [req]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (layout/base-with-req "Table of Contents" req
                               {:description docs-index-description
                                :canonical-path "/docs"}
                               (h/raw (load-docs-index-html)))})

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
