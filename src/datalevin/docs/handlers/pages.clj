(ns datalevin.docs.handlers.pages
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as jio]
            [clojure.string :as str]
            [datalevin.core :as d]
            [datalevin.docs.config :as config]
            [datalevin.docs.util :as util]
            [datalevin.docs.views.layout :as layout]
            [hiccup2.core :as h])
  (:import [java.time Instant]
           [java.time.format DateTimeFormatter]
           [org.commonmark.node Block Code FencedCodeBlock HardLineBreak Heading
            Image IndentedCodeBlock Node SoftLineBreak Text]
           [org.commonmark.ext.footnotes FootnotesExtension]
           [org.commonmark.ext.gfm.tables TablesExtension]
           [org.commonmark.parser Parser]
           [org.commonmark.renderer.html AttributeProvider AttributeProviderFactory HtmlRenderer]))

(def docs-dir "resources/docs")
(def extensions [(FootnotesExtension/create) (TablesExtension/create)])
(def parser* (.. Parser builder (extensions extensions) build))

(defn- child-seq
  [^Node node]
  (when-let [first-child (.getFirstChild node)]
    (->> first-child
         (iterate #(.getNext ^Node %))
         (take-while some?))))

(declare node-text)

(defn- children-text
  [^Node node]
  (->> (child-seq node)
       (map node-text)
       (remove str/blank?)
       (str/join " ")))

(defn- node-text
  [^Node node]
  (cond
    (instance? Text node) (.getLiteral ^Text node)
    (instance? Code node) (.getLiteral ^Code node)
    (instance? SoftLineBreak node) " "
    (instance? HardLineBreak node) " "
    (instance? FencedCodeBlock node) ""
    (instance? IndentedCodeBlock node) ""
    :else (children-text node)))

(defn- normalize-search-text
  [text]
  (some-> text
          (str/replace #"\s+" " ")
          str/trim
          not-empty))

(defn- slugify
  [s]
  (let [slug (-> (or s "")
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"(^-+|-+$)" ""))]
    (if (seq slug) slug "section")))

(defn- unique-anchor!
  [seen heading-text]
  (let [base (slugify heading-text)
        n (get @seen base 0)
        anchor (if (zero? n)
                 base
                 (str base "-" (inc n)))]
    (swap! seen assoc base (inc n))
    anchor))

(defn- code-anchor
  [section-anchor n]
  (str (or section-anchor "top") "-code-" n))

(defn- document-anchor-provider-factory
  []
  (reify AttributeProviderFactory
    (create [_ _context]
      (let [seen (atom {})
            current-heading (atom nil)
            code-count (atom 0)]
        (reify AttributeProvider
          (setAttributes [_ node tag-name attrs]
            (when (and (instance? Heading node)
                       (str/starts-with? tag-name "h"))
              (let [heading-text (or (normalize-search-text (node-text node))
                                      "section")
                    anchor (unique-anchor! seen heading-text)]
                (reset! current-heading anchor)
                (.put attrs "id" anchor)))
            (when (and (#{"pre"} tag-name)
                       (or (instance? FencedCodeBlock node)
                           (instance? IndentedCodeBlock node)))
              (.put attrs "id" (code-anchor @current-heading
                                             (swap! code-count inc))))))))))

(def renderer*
  (.. HtmlRenderer
      builder
      (extensions extensions)
      (attributeProviderFactory (document-anchor-provider-factory))
      build))

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
  "Browse the Datalevin guide by chapter, from getting started and storage fundamentals to search, performance, and operations.")

(def ^:private book-description
  "The complete print book for Datalevin, including the online guide, agent-memory chapters, and full appendices.")

(defn web-visible-frontmatter?
  "Returns true when a document should be shown in the web guide."
  [frontmatter]
  (not (false? (:web frontmatter))))

(defn- book-buy-url
  []
  (some-> (config/env "BOOK_BUY_URL") str/trim not-empty))

(defn- buy-book-link-attrs
  [class]
  (if-let [url (book-buy-url)]
    {:href url :target "_blank" :rel "noopener noreferrer" :class class}
    {:href "/book" :class class}))

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

(def ^:private frontmatter-delimiter
  "---")

(def ^:private max-frontmatter-chars
  8192)

(defn- parse-frontmatter-yaml
  [yaml-text]
  (or (some-> yaml-text not-empty yaml/parse-string)
      {}))

(defn parse-frontmatter [content]
  (let [yaml-re #"^---\n([\s\S]*?)\n---\n([\s\S]*)$"
        matches (re-find yaml-re content)]
    (if matches
      {:frontmatter (parse-frontmatter-yaml (nth matches 1))
       :markdown (nth matches 2)}
      {:frontmatter {} :markdown content})))

(defn parse-markdown [markdown]
  (let [node (.parse parser* markdown)]
    (.render renderer* node)))

(defn substitute-markdown-vars
  "Expands render-time variables used in docs Markdown."
  [markdown]
  (str/replace (or markdown "")
               "{{datalevin-version}}"
               (config/datalevin-version)))

(defn- search-record-base
  [slug frontmatter type key anchor title order]
  (cond-> {:search/key key
           :search/type type
           :search/doc slug}
    order (assoc :search/order order)
    (seq anchor) (assoc :search/anchor anchor)
    (seq title) (assoc :search/title title)
    (:chapter frontmatter) (assoc :search/chapter (:chapter frontmatter))
    (:part frontmatter) (assoc :search/part (:part frontmatter))))

(defn- code-language
  [info]
  (some-> info str/trim (str/split #"\s+") first not-empty))

(defn search-records
  "Build typed search records for one rendered Markdown document.

   The records are ordinary Datalevin entities: sections search prose,
   examples search code fences, and figures search image alt text. Heading and
   code-block anchors are generated with the same algorithm used by
   parse-markdown."
  [slug frontmatter markdown]
  (let [doc-node (.parse parser* (substitute-markdown-vars markdown))
        seen-anchors (atom {})
        current-section (atom nil)
        records (atom [])
        order (atom 0)
        code-count (atom 0)
        figure-count (atom 0)
        next-order! #(swap! order inc)
        current-target (fn []
                         (or @current-section
                             (let [section {:anchor nil
                                            :title (or (:title frontmatter) slug)
                                            :order (next-order!)
                                            :text []}]
                               (reset! current-section section)
                               section)))
        current-anchor #(some-> (current-target) :anchor)
        current-title #(or (:title (current-target))
                           (:title frontmatter)
                           slug)
        flush-section!
        (fn []
          (when-let [{:keys [anchor title text order]} @current-section]
            (when-let [body (normalize-search-text (str/join " " text))]
              (swap! records conj
                     (assoc (search-record-base slug frontmatter :section
                                                (str slug (when anchor (str "#" anchor)))
                                                anchor title order)
                            :search/text body)))))
        append-section-text!
        (fn [text]
          (when (seq text)
            (let [section (current-target)]
              (swap! current-section update :text conj text))))
        start-section!
        (fn [^Heading heading]
          (flush-section!)
          (let [title (or (normalize-search-text (node-text heading))
                          "Section")
                anchor (unique-anchor! seen-anchors title)]
            (reset! current-section {:anchor anchor
                                     :title title
                                     :order (next-order!)
                                     :text []})))
        add-code!
        (fn [literal info]
          (when-let [code (some-> literal str/trim not-empty)]
            (let [n (swap! code-count inc)
                  section-anchor (current-anchor)
                  anchor (code-anchor section-anchor n)
                  title (current-title)
                  key (str slug "#" anchor)]
              (swap! records conj
                     (cond-> (assoc (search-record-base slug frontmatter :example
                                                         key anchor nil (next-order!))
                                     :search/code code
                                     :search/context title)
                       (code-language info) (assoc :search/language (code-language info)))))))
        add-figure!
        (fn [alt-text]
          (when-let [text (normalize-search-text alt-text)]
            (let [n (swap! figure-count inc)
                  anchor (current-anchor)
                  title (current-title)
                  key (str slug
                           (if anchor (str "#" anchor) "#top")
                           ":figure-" n)]
              (swap! records conj
                     (assoc (search-record-base slug frontmatter :figure
                                                key anchor nil (next-order!))
                            :search/text text
                            :search/context title)))))
        visit!
        (fn visit! [^Node node]
          (cond
            (instance? Heading node)
            (start-section! node)

            (instance? FencedCodeBlock node)
            (add-code! (.getLiteral ^FencedCodeBlock node)
                       (.getInfo ^FencedCodeBlock node))

            (instance? IndentedCodeBlock node)
            (add-code! (.getLiteral ^IndentedCodeBlock node) nil)

            (instance? Image node)
            (let [alt-text (node-text node)]
              (add-figure! alt-text)
              (append-section-text! alt-text))

            (or (instance? Text node)
                (instance? Code node)
                (instance? SoftLineBreak node)
                (instance? HardLineBreak node))
            (append-section-text! (node-text node))

            :else
            (do
              (doseq [child (child-seq node)]
                (visit! child))
              (when (instance? Block node)
                (append-section-text! " ")))))]
    (visit! doc-node)
    (flush-section!)
    @records))

(defn- read-frontmatter
  [file]
  (with-open [reader (java.io.BufferedReader. (jio/reader file))]
    (let [first-line (some-> (.readLine reader)
                             (str/replace-first #"^\uFEFF" ""))]
      (if (= frontmatter-delimiter first-line)
        (loop [lines []
               total-chars 0]
          (if-let [line (.readLine reader)]
            (cond
              (= frontmatter-delimiter line)
              (parse-frontmatter-yaml (str/join "\n" lines))

              (> (+ total-chars (count line) 1) max-frontmatter-chars)
              {}

              :else
              (recur (conj lines line)
                     (+ total-chars (count line) 1)))
            {}))
        {}))))

(defn web-visible-slug?
  "Returns true when the Markdown file for `slug` should be shown in the web guide."
  [slug]
  (let [file (jio/file docs-dir (str slug ".md"))]
    (and (.exists file)
         (web-visible-frontmatter? (read-frontmatter file)))))

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
                html (when (web-visible-frontmatter? frontmatter)
                       (parse-markdown (substitute-markdown-vars markdown)))]
            (when html
              (let [doc {:title (:title frontmatter)
                         :chapter (:chapter frontmatter)
                         :part (:part frontmatter)
                         :description (doc-description frontmatter html)
                         :html html}]
                (cache-doc! filename doc))))))))

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
                                 frontmatter (read-frontmatter f)]
                           :when (and (:chapter frontmatter)
                                      (web-visible-frontmatter? frontmatter))]
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
           "<p class=\"mb-8\" style=\"color:var(--text-secondary, #9ca3af)\">This online guide covers the Datalevin-focused chapters. The complete print book also includes AI memory chapters and full appendices. <a href=\"/book\" class=\"hover:underline\" style=\"color:var(--text-link, #22d3ee);\">Learn about the full book</a>.</p>"
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
                     {:path "/book" :priority "0.9"}
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
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body
   (layout/base-with-req "Datalevin Docs" req
                         {:description    home-description
                          :canonical-path "/"}
	                       ;; Hero
                         [:div {:style "text-align:center;padding:4rem 1rem 0"}
                          [:h1 {:style "font-size:3.5rem;font-weight:bold;margin-bottom:1.5rem;background:linear-gradient(135deg,#06b6d4,#3b82f6,#8b5cf6);-webkit-background-clip:text;-webkit-text-fill-color:transparent;background-clip:text;"} "Datalevin"]
                          [:p {:style "font-size:1.25rem;color:#9ca3af;margin-bottom:2rem"}
                           "The database that thinks"]                          [:div {:style "display:flex;justify-content:center;gap:1rem;margin-bottom:3rem"}
                                                                                 [:a {:href "/docs/02-getting-started" :class "dl-btn-primary"} "Get Started"]
                                                                                 [:a {:href "/docs" :class "dl-btn"} "Read the Guide"]]]
                         ;; Features
                         [:div {:style "max-width:64rem;margin:0 auto;padding:2rem 2rem"}
                          [:h2 {:class "section-title" :style "font-size:1.5rem;font-weight:bold;text-align:center;margin-bottom:2rem;color:#f9fafb"} "Why Datalevin?"]
                          [:div {:style "display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:1.5rem"}
                           [:a {:href "/docs/03-mental-model" :class card-style}
                            [:h3 {:style "font-weight:600;margin-bottom:0.5rem;color:#f9fafb"} "Simple"]
                            [:p {:style "color:#9ca3af;font-size:0.875rem"} "One Datalog query language, every data model."]]
                           [:a {:href "/docs/04-storage-fundamentals" :class card-style}
                            [:h3 {:style "font-weight:600;margin-bottom:0.5rem;color:#f9fafb"} "Fast"]
                            [:p {:style "color:#9ca3af;font-size:0.875rem"} "Built on LMDB, the fastest key-value ACID store."]]
                           [:a {:href "/docs/01-why-datalevin" :class card-style}
                            [:h3 {:style "font-weight:600;margin-bottom:0.5rem;color:#f9fafb"} "Versatile"]
                            [:p {:style "color:#9ca3af;font-size:0.875rem"} "Relational, graph, document, logical, vector, full-text in one unified DB."]]]
                          [:div {:style "display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:1.5rem;margin-top:1.5rem"}
                           [:a {:href "/docs/02-getting-started" :class card-style}
                            [:h3 {:style "font-weight:600;margin-bottom:0.5rem;color:#f9fafb"} "Deploy Agnostic"]
                            [:p {:style "color:#9ca3af;font-size:0.875rem"} "Start embedded, move to cluster, never migrate data."]]
                           [:a {:href "/docs/21-query-optimization-profiling" :class card-style}
                            [:h3 {:style "font-weight:600;margin-bottom:0.5rem;color:#f9fafb"} "Performance Optimized"]
                           [:p {:style "color:#9ca3af;font-size:0.875rem"} "WAL and asynchronous transactions + state of the art query optimizer and rule engine."]]
                           [:a {:href "/docs/18-hybrid-queries" :class card-style}
                            [:h3 {:style "font-weight:600;margin-bottom:0.5rem;color:#f9fafb"} "AI Native"]
                            [:p {:style "color:#9ca3af;font-size:0.875rem"} "Built-in embedding, text generation, and OCR, with full-text/vector/idoc retrieval and MCP tools."]]]]
                         ;; Ecosystem
                         [:div {:style "max-width:64rem;margin:0 auto;padding:2rem 2rem"}
                          [:h2 {:class "section-title" :style "font-size:1.5rem;font-weight:bold;text-align:center;margin-bottom:0.75rem;color:#f9fafb"} "Ecosystem"]
                          [:p {:style "text-align:center;color:#9ca3af;font-size:1rem;margin-bottom:1.5rem"}
                           "Sharing ideas and helping each other with questions, examples, and issues."]
                          [:div {:style "display:flex;justify-content:center;gap:1rem;flex-wrap:wrap"}
                           [:a {:href "/examples" :class btn-style} "User Examples"]
                           [:a {:href slack-url :target "_blank" :rel "noopener noreferrer" :class btn-style}
                            (str \# "datalevin Clojurians Slack")]
                           [:a {:href   "https://github.com/datalevin/datalevin/discussions"
                                :target "_blank" :rel "noopener noreferrer" :class btn-style}
                            "GitHub Discussions"]]]
                         ;; Support
                         [:div {:style "max-width:64rem;margin:0 auto;padding:2rem 2rem 3rem"}
                          [:h2 {:class "section-title" :style "font-size:1.5rem;font-weight:bold;text-align:center;margin-bottom:0.75rem;color:#f9fafb"} "Support the Project"]
                          [:p {:style "text-align:center;color:#9ca3af;font-size:1rem;margin-bottom:1.5rem"}
                           "If you enjoy Datalevin and it is helping you succeed, consider sponsoring the development and maintenance of this project."]
                          [:div {:style "display:flex;justify-content:center;gap:1rem;flex-wrap:wrap"}
                           [:a (buy-book-link-attrs "dl-btn-primary")
                            "Buy the book"]
                           [:a {:href "https://github.com/sponsors/huahaiy" :target "_blank" :rel "noopener noreferrer" :class btn-style}
                            "GitHub Sponsors"]]])})

(defn book-cover-svg [_req]
  (if-let [resource (jio/resource "cover/front-cover.svg")]
    {:status 200
     :headers {"Content-Type" "image/svg+xml; charset=utf-8"
               "Cache-Control" "public, max-age=3600"}
     :body (slurp resource)}
    {:status 404
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body "Book cover not found"}))

(defn book-page [req]
  (let [buy-url (book-buy-url)]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body
     (layout/base-with-req
      "The Complete Book" req
      {:description book-description
       :canonical-path "/book"
       :image-path "/book/cover.svg"}
      [:div {:style "max-width:72rem;margin:0 auto;padding:3rem 1.5rem 4rem"}
       [:div {:style "display:grid;grid-template-columns:minmax(220px,360px) minmax(0,1fr);gap:3rem;align-items:start"}
        [:div {:style "display:flex;justify-content:center"}
         [:img {:src "/book/cover.svg"
                :alt "Cover of Datalevin: The Definitive Guide to Logical and Intelligent Databases"
                :style "width:min(100%,360px);height:auto;border-radius:8px;box-shadow:0 24px 80px rgba(0,0,0,0.45);border:1px solid rgba(255,255,255,0.12);"}]]
        [:div
         [:p {:style "color:#7ad9b9;font-size:0.8rem;font-weight:700;letter-spacing:0.16em;text-transform:uppercase;margin-bottom:0.75rem"}
          "Complete Print Edition"]
         [:h1 {:style "color:var(--text-primary,#f9fafb);font-size:2.5rem;line-height:1.1;font-weight:800;margin-bottom:1rem"}
          "Datalevin: The Definitive Guide to Logical and Intelligent Databases"]
         [:p {:style "color:var(--text-secondary,#9ca3af);font-size:1.05rem;line-height:1.7;margin-bottom:1.5rem"}
          "The online guide focuses on Datalevin itself. The complete book goes further: it includes the Datalevin guide, a substantial part on using Datalevin as persistent memory for intelligent systems, and full appendices for installation, EDN, schema, Datalog built-ins, and public APIs."]
         [:div {:style "display:flex;gap:1rem;flex-wrap:wrap;margin-bottom:1.75rem"}
          (if buy-url
            [:a {:href buy-url :target "_blank" :rel "noopener noreferrer" :class "dl-btn-primary"}
             "Buy on Amazon"]
            [:span {:class "dl-btn-primary"
                    :style "opacity:0.72;cursor:default"}
             "Amazon link coming soon"])
          [:a {:href "/docs" :class "dl-btn"} "Read the Online Guide"]]
         [:div {:style "display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:1rem"}
          [:div {:class card-style}
           [:h2 {:style "color:#f9fafb;font-size:1.05rem;font-weight:700;margin-bottom:0.5rem"} "Datalevin Guide"]
           [:p {:style "color:#9ca3af;font-size:0.95rem;line-height:1.55"} "Foundations, transactions, reading APIs, Datalog, rules, modeling, full-text search, vector search, hybrid queries, durability, ingestion, query planning, and operations."]]
          [:div {:class card-style}
           [:h2 {:style "color:#f9fafb;font-size:1.05rem;font-weight:700;margin-bottom:0.5rem"} "AI Memory"]
           [:p {:style "color:#9ca3af;font-size:0.95rem;line-height:1.55"} "Agent memory architecture, episodic and semantic memory, recall and context assembly, apperception, truth maintenance, and stateful AI application patterns built on Datalevin."]]
          [:div {:class card-style}
           [:h2 {:style "color:#f9fafb;font-size:1.05rem;font-weight:700;margin-bottom:0.5rem"} "Full Appendices"]
           [:p {:style "color:#9ca3af;font-size:0.95rem;line-height:1.55"} "Installation and runtime notes, EDN, schema reference, Datalog built-ins, core helpers, key-value API, and client API reference material for print reading."]]]]]])}))

(defn docs-index [req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    (layout/base-with-req "Table of Contents" req
                                  {:description    docs-index-description
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
