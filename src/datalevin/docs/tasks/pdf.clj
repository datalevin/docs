(ns datalevin.docs.tasks.pdf
  (:require [datalevin.docs.handlers.pages :as pages]
            [clojure.java.io :as jio]
            [clj-yaml.core :as yaml]
            [taoensso.timbre :as log])
  (:import [org.commonmark.parser Parser]
           [org.commonmark.renderer.html HtmlRenderer]
           [java.io File]))

(def docs-dir "resources/docs")
(def pdf-cache-dir (or (System/getenv "PDF_CACHE_PATH") "data/pdf-cache"))
(def parser* (.. Parser builder build))
(def renderer* (.. HtmlRenderer builder build))

(defn parse-markdown [markdown]
  (.render renderer* (.parse parser* markdown)))

;; Cache for PDF generation to avoid repeated file I/O and parsing
(defonce ^:private pdf-chapters-cache (atom nil))

(defn- clear-pdf-cache!
  "Clear PDF cache. Call when docs are updated."
  []
  (reset! pdf-chapters-cache nil))

(defn load-all-chapters []
  (or @pdf-chapters-cache
      (let [dir (jio/file docs-dir)
            files (filter #(.endsWith (.getName %) ".md") (file-seq dir))
            chapters (for [f files
                           :let [filename (.getName f)]
                           :when (not (or (= filename "toc.md")
                                          (= filename "preface.md")))
                           :let [content (slurp f)
                                 {:keys [frontmatter markdown]} (pages/parse-frontmatter content)
                                 chapter-num (or (:chapter frontmatter) 0)]
                           :when (and (number? chapter-num) (> chapter-num 0))]
                       {:filename filename
                        :chapter chapter-num
                        :title (:title frontmatter)
                        :part (:part frontmatter)
                        :markdown markdown
                        :html (parse-markdown markdown)})
            sorted (sort-by :chapter chapters)]
        (reset! pdf-chapters-cache sorted)
        sorted)))

(def print-styles
  "<style>
    @page { margin: 1in; }
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.6; color: #333; max-width: 800px; margin: 0 auto; padding: 2rem; }
    h1 { font-size: 2rem; margin-top: 2rem; border-bottom: 2px solid #333; padding-bottom: 0.5rem; }
    h2 { font-size: 1.5rem; margin-top: 1.5rem; color: #444; }
    h3 { font-size: 1.25rem; margin-top: 1rem; }
    pre { background: #f5f5f5; padding: 1rem; overflow-x: auto; border-radius: 4px; }
    code { background: #f5f5f5; padding: 0.2rem 0.4rem; border-radius: 3px; font-size: 0.9em; }
    pre code { background: none; padding: 0; }
    .chapter { page-break-before: always; }
    .chapter:first-child { page-break-before: avoid; }
    .chapter-title { font-size: 1.75rem; font-weight: bold; margin-bottom: 1rem; }
    .chapter-meta { color: #666; font-size: 0.875rem; margin-bottom: 2rem; }
    table { border-collapse: collapse; width: 100%; margin: 1rem 0; }
    th, td { border: 1px solid #ddd; padding: 0.5rem; text-align: left; }
    th { background: #f5f5f5; }
    blockquote { border-left: 4px solid #ddd; margin: 1rem 0; padding-left: 1rem; color: #666; }
  </style>")

(defn- pandoc-available? []
  (try
    (let [proc (.start (ProcessBuilder. ["pandoc" "--version"]))]
      (zero? (.waitFor proc)))
    (catch Exception _ false)))

(defn- build-combined-markdown [chapters]
  (apply str
    (for [ch chapters]
      (str "\n\n# Chapter " (:chapter ch) ": " (:title ch) "\n\n"
           (:markdown ch)))))

(defn- build-html [chapters]
  (let [body (apply str
               (for [ch chapters]
                 (str "<div class='chapter'>"
                      "<div class='chapter-title'>Chapter " (:chapter ch) ": " (:title ch) "</div>"
                      (:html ch)
                      "</div>")))]
    (str "<!DOCTYPE html><html><head><meta charset='utf-8'><title>Datalevin Book</title>"
         print-styles
         "</head><body>" body "</body></html>")))

(defn- generate-pdf-via-pandoc [chapters]
  (.mkdirs (jio/file pdf-cache-dir))
  (let [md-file (jio/file pdf-cache-dir "datalevin-book.md")
        pdf-file (jio/file pdf-cache-dir "datalevin-book.pdf")
        combined-md (build-combined-markdown chapters)]
    (spit md-file combined-md)
    (let [proc (-> (ProcessBuilder.
                     ["pandoc" (.getAbsolutePath md-file)
                      "-o" (.getAbsolutePath pdf-file)
                      "--pdf-engine=xelatex"
                      "-V" "geometry:margin=1in"
                      "-V" "fontsize=11pt"
                      "-V" "mainfont=DejaVu Sans"
                      "-V" "monofont=DejaVu Sans Mono"
                      "--highlight-style=tango"
                      "--toc"
                      "--toc-depth=2"
                      "-s"])
                   (.redirectErrorStream true)
                   .start)
          output (slurp (.getInputStream proc))
          exit (.waitFor proc)]
      (jio/delete-file md-file true)
      (if (and (zero? exit) (.exists pdf-file))
        pdf-file
        (do
          (log/warn "Pandoc exited" exit ":" output)
          nil)))))

(defn pdf-handler [{:keys [session] :as req}]
  (if-not (:user session)
    {:status 302 :headers {"Location" "/auth/login"}}
    (let [chapters (load-all-chapters)]
      (if (pandoc-available?)
        (if-let [pdf-file (generate-pdf-via-pandoc chapters)]
          {:status 200
           :headers {"Content-Type" "application/pdf"
                     "Content-Disposition" "attachment; filename=\"datalevin-book.pdf\""
                     "Content-Length" (str (.length pdf-file))}
           :body (jio/input-stream pdf-file)}
          ;; Pandoc failed, fall back to HTML
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"
                     "Content-Disposition" "attachment; filename=\"datalevin-book.html\""}
           :body (build-html chapters)})
        ;; No pandoc, serve HTML
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"
                   "Content-Disposition" "attachment; filename=\"datalevin-book.html\""}
         :body (build-html chapters)}))))
