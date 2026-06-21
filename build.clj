(ns build
  (:require [clojure.edn :as edn]
            [clojure.java.io :as jio]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.build.api :as b])
  (:import [java.util.regex Pattern]))

(def class-dir "target/classes")
(def uber-file "target/datalevin-docs-standalone.jar")
(def docs-dir "resources/docs")
(def pdf-dir "target/pdf")
(def converted-image-dir (str pdf-dir "/images"))
(def index-terms-file (str docs-dir "/index-terms.edn"))
(def final-pdf-stem "datalevin-definitive-guide")
(def final-md-file (str pdf-dir "/" final-pdf-stem ".md"))
(def final-tex-file (str pdf-dir "/" final-pdf-stem ".tex"))
(def final-pdf-file (str pdf-dir "/" final-pdf-stem ".pdf"))
(def default-datalevin-version "1.0.x")
(def basis (delay (b/create-basis {:project "deps.edn"
                                   :aliases [:prod]})))

(def part-titles
  {"I" "Foundations: A Multi-Paradigm Database"
   "II" "Core APIs: Datalog First, KV When Needed"
   "III" "Modeling Across Paradigms"
   "IV" "Indexes as Capabilities"
   "V" "Performance and Operations"
   "VI" "Datalevin for Intelligent Systems"
   "VII" "Appendices"})

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :class-dir class-dir
                  :ns-compile '[datalevin.docs.core]})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'datalevin.docs.core})
  (println "Built:" uber-file))

(defn- strip-frontmatter
  [markdown]
  (str/replace-first markdown #"(?s)^---\n.*?\n---\n" ""))

(defn- substitute-vars
  [markdown]
  (str/replace markdown
               "{{datalevin-version}}"
               (or (some-> (System/getenv "DATALEVIN_VERSION")
                           str/trim
                           not-empty)
                   (some-> (System/getProperty "DATALEVIN_VERSION")
                           str/trim
                           not-empty)
                   default-datalevin-version)))

(defn- rewrite-image-paths
  [markdown]
  (-> markdown
      (str/replace #"\]\(/images/([^)]+?)\.svg\)"
                   (fn [[_ path]]
                     (str "](target/pdf/images/" path ".pdf)")))
      (str/replace "](/images/" "](resources/public/images/")))

(defn- remove-multi-lang-html
  [markdown]
  (-> markdown
      (str/replace #"(?m)^\s*<div class=\"multi-lang\">\s*\n?" "")
      (str/replace #"(?m)^\s*</div>\s*\n?" "")))

(defn- show-external-link-urls
  [markdown]
  (letfn [(external-url? [s]
            (boolean (re-matches #"https?://\S+" s)))
          (matching-delimiter [^String s start open close]
            (let [n (count s)]
              (loop [i start
                     depth 1]
                (cond
                  (>= i n) nil

                  (= \\ (.charAt s i))
                  (recur (+ i 2) depth)

                  (= open (.charAt s i))
                  (recur (inc i) (inc depth))

                  (= close (.charAt s i))
                  (let [depth' (dec depth)]
                    (if (zero? depth')
                      i
                      (recur (inc i) depth')))

                  :else
                  (recur (inc i) depth)))))
          (visible-link [label url]
            (let [label (str/trim label)]
              (if (= label url)
                (str "<" url ">")
                (str label " (<" url ">)"))))
          (rewrite-links [^String s]
            (let [n (count s)
                  out (StringBuilder.)]
              (loop [i 0]
                (if (>= i n)
                  (str out)
                  (if (and (= \[ (.charAt s i))
                           (not (and (pos? i)
                                     (= \! (.charAt s (dec i))))))
                    (let [label-end (matching-delimiter s (inc i) \[ \])
                          url-open (some-> label-end inc)]
                      (if (and label-end
                               (< url-open n)
                               (= \( (.charAt s url-open)))
                        (let [url-end (matching-delimiter s (inc url-open) \( \))
                              label (subs s (inc i) label-end)
                              url (when url-end
                                    (subs s (inc url-open) url-end))]
                          (if (and url (external-url? url))
                            (do
                              (.append out (visible-link label url))
                              (recur (inc url-end)))
                            (do
                              (.append out (.charAt s i))
                              (recur (inc i)))))
                        (do
                          (.append out (.charAt s i))
                          (recur (inc i)))))
                    (do
                      (.append out (.charAt s i))
                      (recur (inc i))))))))
          (flush-prose [out lines]
            (cond-> out
              (seq lines)
              (conj (rewrite-links (str (str/join "\n" lines) "\n")))))]
    (let [{:keys [out prose-lines in-code?]}
          (reduce
           (fn [{:keys [out prose-lines in-code?] :as state} line]
             (cond
               (and in-code? (re-matches #"^\s*```\s*$" line))
               (-> state
                   (update :out conj (str line "\n"))
                   (assoc :in-code? false))

               in-code?
               (update state :out conj (str line "\n"))

               (re-matches #"^\s*```.*$" line)
               (-> state
                   (assoc :out (conj (flush-prose out prose-lines)
                                     (str line "\n"))
                          :prose-lines []
                          :in-code? true))

               :else
               (update state :prose-lines conj line)))
           {:out [] :prose-lines [] :in-code? false}
           (str/split-lines markdown))]
      (when in-code?
        (throw (ex-info "Unclosed fenced code block while rewriting PDF links"
                        {})))
      (apply str (flush-prose out prose-lines)))))

(defn- clojure-fence?
  [info]
  (let [lang (-> info str/trim (str/split #"\s+") first str/lower-case)]
    (or (= lang "clojure")
        (= lang "clj")
        (= lang "{.clojure}"))))

(defn- pdf-keep-text-fence?
  [info]
  (let [info (-> info str/trim str/lower-case)
        lang (-> info (str/split #"\s+") first)]
    (and (contains? #{"text" "plain" "{.text" "{.plain"} lang)
         (re-find #"(?<![\w-])pdf-keep(?![\w-])" info))))

(defn- console-fence?
  [info]
  (let [lang (-> info str/trim (str/split #"\s+") first str/lower-case)]
    (contains? #{"console" "{.console}"} lang)))

(defn- prolog-fence?
  [info]
  (let [lang (-> info str/trim (str/split #"\s+") first str/lower-case)]
    (contains? #{"prolog" "{.prolog}"} lang)))

(defn- pdf-fence-info
  [info]
  (cond
    (clojure-fence? info) "clojure"
    (pdf-keep-text-fence? info) "text"
    (console-fence? info) "text"
    (prolog-fence? info) "text"
    :else nil))

(defn- keep-clojure-fences-only
  [markdown]
  (let [{:keys [lines keep? in-code?]}
        (reduce
         (fn [{:keys [keep? in-code?] :as state} line]
           (if in-code?
             (if (re-matches #"^\s*```\s*$" line)
               (cond-> (assoc state :in-code? false :keep? true)
                 keep? (update :lines conj line))
               (cond-> state
                 keep? (update :lines conj line)))
             (if-let [[_ indent info] (re-matches #"^(\s*)```(.*)$" line)]
               (let [fence-info (pdf-fence-info info)]
                 (cond-> (assoc state :in-code? true :keep? (some? fence-info))
                   fence-info (update :lines conj (str indent "```" fence-info))))
               (update state :lines conj line))))
         {:lines [] :keep? true :in-code? false}
         (str/split-lines markdown))]
    (when in-code?
      (throw (ex-info "Unclosed fenced code block while preparing PDF source"
                      {})))
    (str (str/join "\n" lines) "\n")))

(defn- latex-index-escape
  [s]
  (-> s
      (str/replace "\\" "\\textbackslash{}")
      (str/replace #"([&%$#_{}])" "\\\\$1")
      (str/replace "~" "\\textasciitilde{}")
      (str/replace "^" "\\textasciicircum{}")))

(defn- makeindex-escape
  [s]
  (-> s
      (str/replace "\"" "\"\"")
      (str/replace "!" "\"!")
      (str/replace "@" "\"@")
      (str/replace "|" "\"|")))

(defn- function-index-entry
  [function-name]
  (str (makeindex-escape function-name)
       "@\\texttt{"
       (makeindex-escape (latex-index-escape function-name))
       "}"))

(defn- default-term-pattern
  [term]
  (str "(?<![\\p{L}\\p{N}_:/.-])"
       (Pattern/quote term)
       "(?![\\p{L}\\p{N}_:/.-])"))

(defn- compile-index-patterns
  [term patterns case-sensitive?]
  (->> (or (some->> patterns seq)
           [(default-term-pattern term)])
       (mapv (fn [pattern]
               (Pattern/compile
                (if case-sensitive?
                  pattern
                  (str "(?iu)" pattern)))))))

(defn- compile-index-term
  [{:keys [term patterns code-patterns case-sensitive?] :as entry}]
  (assoc entry
         :compiled-patterns (compile-index-patterns term
                                                    patterns
                                                    case-sensitive?)
         :compiled-code-patterns (when (seq code-patterns)
                                   (compile-index-patterns term
                                                           code-patterns
                                                           case-sensitive?))))

(defn- index-terms
  []
  (->> (edn/read-string (slurp index-terms-file))
       (mapv compile-index-term)))

(defn- index-command
  [{:keys [term index raw-index]}]
  (str "\\index{" (or raw-index
                      (latex-index-escape (or index term)))
       "}"))

(defn- indexable-prose-line?
  [line]
  (and (not (str/blank? line))
       (not (str/starts-with? (str/triml line) "#"))
       (not (str/starts-with? (str/triml line) "!["))
       (not (str/starts-with? (str/triml line) "\\"))))

(declare table-cells
         table-separator-row?
         fit-pipe-table-widths
         make-inline-code-breakable
         inline-code-spans
         unqualify-function-name
         function-name-token?)

(defn- matching-function-table-terms
  [line terms]
  (if-let [cells (table-cells line)]
    (let [names (->> (inline-code-spans (first cells))
                     (map unqualify-function-name)
                     (filter function-name-token?)
                     set)]
      (filterv #(contains? names (:term %)) terms))
    []))

(defn- insert-index-commands-in-first-table-cell
  [line index-line]
  (let [first-pipe (.indexOf line "|")
        second-pipe (when-not (neg? first-pipe)
                      (.indexOf line "|" (inc first-pipe)))]
    (if (and second-pipe (not (neg? second-pipe)))
      (str (subs line 0 second-pipe)
           " "
           index-line
           (subs line second-pipe))
      (str line index-line))))

(defn- matching-index-terms
  ([line terms]
   (matching-index-terms line terms :compiled-patterns))
  ([line terms pattern-key]
   (filterv (fn [{:keys [compiled-patterns] :as term}]
              (some #(re-find % line)
                    (or (seq (get term pattern-key))
                        compiled-patterns)))
           terms)))

(defn- inject-index-entries
  [markdown terms]
  (let [{:keys [lines in-code?]}
        (reduce
         (fn [{:keys [remaining in-code? code-matches in-function-table?] :as state} line]
           (cond
             (and in-code? (re-matches #"^\s*```\s*$" line))
             (let [matches (vec (distinct code-matches))
                   matched? (set matches)
                   index-line (apply str (map index-command matches))]
               (cond-> (-> state
                           (update :lines conj line)
                           (assoc :in-code? false
                                  :code-matches []
                                  :in-function-table? false
                                  :remaining (vec (remove matched? remaining))))
                 (seq matches) (update :lines conj index-line)))

             (re-matches #"^\s*```.*$" line)
             (-> state
                 (update :lines conj line)
                 (assoc :in-code? true
                        :code-matches []
                        :in-function-table? false))

             (and (not in-code?) (table-cells line))
             (let [cells (table-cells line)
                   first-cell (-> cells first str/lower-case)]
               (cond
                 (= first-cell "function")
                 (-> state
                     (update :lines conj line)
                     (assoc :in-function-table? true))

                 (table-separator-row? cells)
                 (update state :lines conj line)

                 in-function-table?
                 (let [matches (matching-function-table-terms line remaining)
                       matched? (set matches)
                       index-line (apply str (map index-command matches))]
                   (-> state
                       (update :lines conj
                               (if (seq matches)
                                 (insert-index-commands-in-first-table-cell
                                  line
                                  index-line)
                                 line))
                       (assoc :remaining
                              (vec (remove matched? remaining)))))

                 :else
                 (-> state
                     (update :lines conj line)
                     (assoc :in-function-table? false))))

             (or in-code?
                 (empty? remaining))
             (if in-code?
               (let [matches (matching-index-terms line
                                                   remaining
                                                   :compiled-code-patterns)]
                 (-> state
                     (update :lines conj line)
                     (update :code-matches into matches)))
               (update state :lines conj line))

             (not (indexable-prose-line? line))
             (-> state
                 (update :lines conj line)
                 (assoc :in-function-table? false))

             :else
             (let [matches (matching-index-terms line remaining)
                   matched? (set matches)]
               (-> state
                   (update :lines conj
                           (str line (apply str (map index-command matches))))
                   (assoc :remaining
                          (vec (remove matched? remaining))
                          :in-function-table? false)))))
         {:lines []
          :remaining terms
          :in-code? false
          :code-matches []
          :in-function-table? false}
         (str/split-lines markdown))]
    (when in-code?
      (throw (ex-info "Unclosed fenced code block while injecting index entries"
                      {})))
    (str (str/join "\n" lines) "\n")))

(defn- update-first-h1
  [markdown f]
  (let [{:keys [lines updated?]}
        (reduce
         (fn [{:keys [updated?] :as state} line]
           (if (and (not updated?) (str/starts-with? line "# "))
             (-> state
                 (update :lines conj (f line))
                 (assoc :updated? true))
             (update state :lines conj line)))
         {:lines [] :updated? false}
         (str/split-lines markdown))]
    (str (str/join "\n" lines) "\n")))

(defn- normalize-front-heading
  [markdown]
  (update-first-h1
   markdown
   #(str/replace % #"^# (.*)$" "# $1 {.unnumbered .unlisted}")))

(defn- normalize-preface-heading
  [markdown]
  (update-first-h1
   markdown
   #(str (str/replace % #"^# (.*)$" "# $1 {.unnumbered}")
         "\n\n\\markboth{Preface}{Preface}")))

(defn- normalize-chapter-heading
  [markdown]
  (update-first-h1
   markdown
   #(-> %
        (str/replace #"^# Chapter \d+:\s*" "# ")
        (str/replace #"^# Appendix [A-Z]:\s*" "# "))))

(defn- replace-code-listing-markers
  [markdown]
  (str/replace
   markdown
   #"(?m)^<!--\s*pdf-listing:\s*(.+?)\s*-->\s*$"
   (fn [[_ title]]
     (str "\n\\dlcodelisting{" (latex-index-escape (str/trim title)) "}\n"))))

(defn- prepare-markdown
  ([file heading-fn]
   (prepare-markdown file heading-fn nil))
  ([file heading-fn terms]
   (let [markdown (-> file
                      slurp
                      strip-frontmatter
                      substitute-vars
                      rewrite-image-paths
                      remove-multi-lang-html
                      keep-clojure-fences-only
                      show-external-link-urls
                      heading-fn
                      fit-pipe-table-widths
                      replace-code-listing-markers)
         indexed (cond-> markdown
                   (seq terms) (inject-index-entries terms))]
     (make-inline-code-breakable indexed))))

(defn- title-metadata
  []
  (let [lines (-> (jio/file docs-dir "title.md")
                  slurp
                  strip-frontmatter
                  substitute-vars
                  str/split-lines)
        nonblank (->> lines
                      (map str/trim)
                      (remove str/blank?)
                      vec)
        title (some #(when-let [[_ v] (re-matches #"^#\s+(.+)$" %)] v)
                    nonblank)
        subtitle (some #(when-let [[_ v] (re-matches #"^##\s+(.+)$" %)] v)
                       nonblank)
        body (->> nonblank
                  (remove #(str/starts-with? % "#"))
                  vec)]
    {:title title
     :subtitle subtitle
     :author (first body)
     :date (second body)
     :note-lines (drop 2 body)}))

(defn- yaml-quote
  [s]
  (pr-str (or s "")))

(def pdf-header-includes
  ["\\usepackage{xcolor}"
   "\\definecolor{dlblue}{HTML}{0F4C81}"
   "\\definecolor{dlgray}{HTML}{2F3437}"
   "\\definecolor{shadecolor}{HTML}{F5F7FA}"
   "\\usepackage{microtype}"
   "\\usepackage{ragged2e}"
   "\\usepackage{titlesec}"
   "\\usepackage{fancyhdr}"
   "\\usepackage[font=small,labelfont=bf]{caption}"
   "\\usepackage{makeidx}"
   "\\usepackage{fvextra}"
   "\\usepackage{xurl}"
   "\\usepackage{newunicodechar}"
   "\\usepackage{needspace}"
   "\\makeindex"
   "\\AtBeginDocument{\\RaggedRight\\sloppy}"
   "\\setlength{\\tabcolsep}{4pt}"
   "\\setlength{\\LTpre}{0.4\\baselineskip}"
   "\\setlength{\\LTpost}{0.4\\baselineskip}"
   "\\renewcommand{\\arraystretch}{0.94}"
   "\\newunicodechar{→}{\\ensuremath{\\to}}"
   "\\newcounter{dlcode}"
   "\\numberwithin{dlcode}{chapter}"
   "\\newcommand{\\dlcodelisting}[1]{\\Needspace{6\\baselineskip}\\refstepcounter{dlcode}\\par\\medskip{\\small\\sffamily\\bfseries\\color{dlgray} Listing \\thedlcode. #1\\par}\\smallskip}"
   "\\captionsetup[figure]{justification=centering,singlelinecheck=false}"
   "\\emergencystretch=3em"
   "\\fvset{breaklines=true,breakanywhere=true,fontsize=\\small}"
   "\\titleformat{\\chapter}[display]{\\sffamily\\bfseries\\raggedright\\color{dlblue}}{\\large\\MakeUppercase{\\chaptertitlename}\\ \\thechapter}{0.5em}{\\huge}"
   "\\titlespacing*{\\chapter}{0pt}{-4pt}{20pt}"
   "\\titleformat{\\section}{\\sffamily\\Large\\bfseries\\color{dlgray}}{\\thesection}{0.75em}{}"
   "\\titleformat{\\subsection}{\\sffamily\\large\\bfseries\\color{dlgray}}{\\thesubsection}{0.75em}{}"
   "\\titleformat{\\subsubsection}{\\sffamily\\normalsize\\bfseries\\color{dlgray}}{\\thesubsubsection}{0.75em}{}"
   "\\titleformat{\\part}[display]{\\thispagestyle{empty}\\sffamily\\bfseries\\centering\\color{dlblue}}{\\Large\\MakeUppercase{\\partname}\\ \\thepart}{1em}{\\huge}"
   "\\makeatletter"
   "\\renewcommand{\\chaptermark}[1]{\\markboth{\\@chapapp\\ \\thechapter: #1}{\\@chapapp\\ \\thechapter: #1}}"
   "\\renewcommand{\\sectionmark}[1]{\\markright{#1}}"
   "\\makeatother"
   "\\pagestyle{fancy}"
   "\\fancyhf{}"
   "\\fancyhead[LE,RO]{\\sffamily\\small\\thepage}"
   "\\fancyhead[RE]{\\sffamily\\small\\nouppercase{\\leftmark}}"
   "\\fancyhead[LO]{\\sffamily\\small\\nouppercase{\\rightmark}}"
   "\\fancypagestyle{plain}{\\fancyhf{}\\fancyhead[LE,RO]{\\sffamily\\small\\thepage}\\fancyhead[RE]{\\sffamily\\small\\nouppercase{\\leftmark}}\\fancyhead[LO]{\\sffamily\\small\\nouppercase{\\rightmark}}\\renewcommand{\\headrulewidth}{0.4pt}}"
   "\\renewcommand{\\headrulewidth}{0.4pt}"
   "\\setlength{\\headheight}{14pt}"
   "\\makeatletter"
   "\\renewcommand{\\maketitle}{%"
   "\\begin{titlepage}"
   "\\thispagestyle{empty}"
   "\\vspace*{0.14\\textheight}"
   "{\\raggedright\\sffamily\\bfseries\\color{dlblue}\\fontsize{24}{30}\\selectfont\\@title\\par}"
   "\\vfill"
   "{\\sffamily\\Large\\@author\\par}"
   "\\vspace{0.75em}"
   "{\\sffamily\\large\\@date\\par}"
   "\\end{titlepage}}"
   "\\makeatother"])

(defn- yaml-literal-list-item
  [lines]
  (str "  - |\n"
       (apply str (map #(str "    " % "\n") lines))))

(defn- pdf-metadata-block
  [{:keys [title subtitle author date]}]
  (str "---\n"
       "title: " (yaml-quote title) "\n"
       "subtitle: " (yaml-quote subtitle) "\n"
       "author: " (yaml-quote author) "\n"
       "date: " (yaml-quote date) "\n"
       "documentclass: book\n"
       "classoption:\n"
       "  - twoside\n"
       "  - openany\n"
       "fontsize: 10pt\n"
       "linestretch: 1.08\n"
       "numbersections: true\n"
       "secnumdepth: 0\n"
       "geometry:\n"
       "  - paperwidth=7in\n"
       "  - paperheight=9.25in\n"
       "  - margin=0.65in\n"
       "mainfont: Charter\n"
       "sansfont: Avenir Next\n"
       "monofont: Menlo\n"
       "mainfontoptions:\n"
       "  - Ligatures=TeX\n"
       "sansfontoptions:\n"
       "  - Ligatures=TeX\n"
       "monofontoptions:\n"
       "  - Scale=0.95\n"
       "  - Ligatures=NoCommon\n"
       "colorlinks: true\n"
       "linkcolor: black\n"
       "urlcolor: dlblue\n"
       "toccolor: black\n"
       "hyperrefoptions:\n"
       "  - pdfdisplaydoctitle\n"
       "  - bookmarksopen=true\n"
       "  - bookmarksopenlevel=1\n"
       "header-includes:\n"
       (yaml-literal-list-item pdf-header-includes)
       "---\n\n"))

(defn- latex-escape
  [s]
  (-> s
      (str/replace "\\" "\\textbackslash{}")
      (str/replace #"([&%$#_{}])" "\\\\$1")
      (str/replace "~" "\\textasciitilde{}")
      (str/replace "^" "\\textasciicircum{}")))

(def inline-code-break-chars
  #{\/ \. \- \_ \: \? \! \+ \= \< \>})

(defn- breakable-inline-code-latex
  [code]
  (let [index-commands (apply str (re-seq #"\\index\{[^}]+\}" code))
        code (-> code
                 (str/replace #"\\index\{[^}]+\}" "")
                 (str/replace #"\s+" " ")
                 str/trim)
        body (apply str
                    (mapcat
                     (fn [ch]
                       (cond-> [(latex-escape (str ch))]
                         (contains? inline-code-break-chars ch)
                         (conj "\\allowbreak{}")))
                     code))]
    (str "\\texttt{" body "}" index-commands)))

(defn- heading-start?
  [^String s idx]
  (let [n (count s)]
    (loop [i idx]
      (cond
        (>= i n) false
        (Character/isWhitespace (.charAt s i)) (recur (inc i))
        :else (= \# (.charAt s i))))))

(defn- copy-until-line-end
  [^String s ^StringBuilder out idx]
  (let [n (count s)]
    (loop [i idx]
      (if (>= i n)
        i
        (let [ch (.charAt s i)]
          (.append out ch)
          (if (= ch \newline)
            (inc i)
            (recur (inc i))))))))

(defn- break-inline-code-spans
  [^String markdown]
  (let [n (count markdown)
        out (StringBuilder.)]
    (loop [idx 0
           line-start? true]
      (if (>= idx n)
        (str out)
        (cond
          (and line-start? (heading-start? markdown idx))
          (recur (copy-until-line-end markdown out idx) true)

          (= \` (.charAt markdown idx))
          (let [end (.indexOf markdown "`" (inc idx))]
            (if (neg? end)
              (do
                (.append out (.charAt markdown idx))
                (recur (inc idx) false))
              (do
                (.append out
                         (breakable-inline-code-latex
                          (subs markdown (inc idx) end)))
                (recur (inc end) false))))

          :else
          (let [ch (.charAt markdown idx)]
            (.append out ch)
            (recur (inc idx) (= ch \newline))))))))

(defn- flush-inline-code-prose
  [out lines]
  (cond-> out
    (seq lines)
    (conj (break-inline-code-spans (str (str/join "\n" lines) "\n")))))

(defn- make-inline-code-breakable
  [markdown]
  (let [{:keys [lines prose-lines in-code?]}
        (reduce
         (fn [{:keys [in-code?] :as state} line]
           (cond
             (re-matches #"^\s*```.*$" line)
             (-> state
                 (assoc :lines (flush-inline-code-prose (:lines state)
                                                        (:prose-lines state))
                        :prose-lines [])
                 (update :lines conj (str line "\n"))
                 (update :in-code? not))

             in-code?
             (update state :lines conj (str line "\n"))

             :else
             (update state :prose-lines conj line)))
         {:lines [] :prose-lines [] :in-code? false}
         (str/split-lines markdown))]
    (when in-code?
      (throw (ex-info "Unclosed fenced code block while preparing inline code"
                      {})))
    (apply str (flush-inline-code-prose lines prose-lines))))

(defn- raw-latex
  [s]
  (str "\n" s "\n\n"))

(defn- frontmatter-note
  [note-lines]
  (when (seq note-lines)
    (str "\\begin{center}\n"
         (str/join "\\\\\n" (map latex-escape note-lines))
         "\n\\end{center}\n\n"
         "\\clearpage\n\n")))

(defn- markdown-paragraphs
  [markdown]
  (->> (str/split markdown #"\n\s*\n")
       (map str/trim)
       (remove str/blank?)))

(defn- dedication-page
  [file]
  (let [paragraphs (-> file
                       slurp
                       strip-frontmatter
                       substitute-vars
                       markdown-paragraphs)
        first-line (first paragraphs)
        body-lines (rest paragraphs)]
    (str "\\thispagestyle{plain}\n"
         "\\vspace*{\\fill}\n"
         "\\begin{center}\n"
         "{\\itshape " (latex-escape first-line) "}\\\\[1.25em]\n"
         "\\begin{minipage}{0.66\\textwidth}\n"
         "\\centering\n"
         (str/join "\n\n" (map latex-escape body-lines))
         "\n\\end{minipage}\n"
         "\\end{center}\n"
         "\\vspace*{\\fill}\n")))

(defn- chapter-files
  []
  (->> (.listFiles (jio/file docs-dir))
       (filter #(.isFile %))
       (filter #(re-matches #"\d{2}-.*\.md" (.getName %)))
       (sort-by #(.getName %))))

(def function-operator-names
  #{"!=" "*" "+" "-" "/" "<" "<=" "=" "==" ">" ">="})

(def qualified-function-qualifier-pattern
  (str "(?:d|cl|i|main|srv|str|su|u|udf|"
       "datalevin\\.[A-Za-z0-9_.-]+|"
       "clojure\\.[A-Za-z0-9_.-]+|"
       "my-app\\.[A-Za-z0-9_.-]+)"))

(def extra-function-index-names
  #{"abort-transact-kv"
    "add-vec"
    "cardinality"
    "close"
    "close-db"
    "close-embedding-provider"
    "close-kv"
    "close-vector-index"
    "conn-from-datoms"
    "conn-from-db"
    "copy"
    "create-conn"
    "create-snapshot!"
    "datalog-index-cache-limit"
    "datalog-kv"
    "datom"
    "datoms"
    "definterfn"
    "defpodfn"
    "empty-db"
    "entity"
    "exec-code"
    "fill-db"
    "force-vec-checkpoint!"
    "get-conn"
    "get-datoms"
    "get-max-eid"
    "get-schema"
    "get-value"
    "idoc-get"
    "idoc-match"
    "init-db"
    "inter-fn"
    "inter-fn-from-reader"
    "inter-fn?"
    "listen!"
    "load-edn"
    "max-eid"
    "new-vector-index"
    "open-kv"
    "pull"
    "q"
    "re-index"
    "remove-vec"
    "resolve-tempid"
    "search-datoms"
    "search-index-writer"
    "search-vec"
    "seek-datoms"
    "sync"
    "transact"
    "transact!"
    "transact-async"
    "transact-kv"
    "transact-kv-async"
    "tx-data->simulated-report"
    "unlisten!"
    "update-schema"
    "vector-checkpoint-state"
    "vector-index-info"
    "with-transaction"
    "with-transaction-kv"
    "write"})

(defn- book-content-files
  []
  (into [(jio/file docs-dir "preface.md")]
        (chapter-files)))

(defn- read-book-markdown
  [file]
  (-> file
      slurp
      strip-frontmatter
      substitute-vars))

(defn- table-cells
  [line]
  (when (str/starts-with? (str/triml line) "|")
    (->> (str/split line #"\|" -1)
         (drop 1)
         drop-last
         (map str/trim)
         vec)))

(defn- table-separator-row?
  [cells]
  (and (seq cells)
       (every? #(re-matches #":?-{3,}:?" %) cells)))

(defn- table-separator-dash-count
  [cell]
  (count (filter #{\-} cell)))

(defn- separator-cell
  [cell width]
  (let [cell (str/trim cell)]
    (str (when (str/starts-with? cell ":") ":")
         (apply str (repeat width "-"))
         (when (str/ends-with? cell ":") ":"))))

(defn- markdown-width-text
  [cell]
  (-> cell
      (str/replace #"`([^`]+)`" "$1")
      (str/replace #"\[([^\]]+)\]\([^)]+\)" "$1")
      (str/replace #"<https?://[^>]+>" "URL")
      (str/replace #"[*_~]" "")
      (str/replace #"\\index\{[^}]+\}" "")
      str/trim))

(defn- normalized-table-header
  [header]
  (-> header
      markdown-width-text
      str/lower-case
      (str/replace #"\s+" " ")
      str/trim))

(defn- width-header-kind
  [header]
  (let [header (normalized-table-header header)]
    (cond
      (contains? #{"meaning" "description" "purpose" "use" "use it when"
                   "best for" "notes" "result shape" "index behavior"
                   "what changes" "start here when" "risk" "domain membership"}
                 header)
      :prose

      (contains? #{"function" "property" "option" "key" "field" "type"
                   "value type" "range type" "mode" "area" "feature"
                   "need" "shape" "syntax" "default" "values" "edn form"
                   "common forms" "operations" "namespace" "form"
                   "original token" "indexed term" "position" "offset"}
                 header)
      :label

      :else
      :neutral)))

(defn- cell-width-score
  [cell]
  (let [text (markdown-width-text cell)
        code-spans (inline-code-spans cell)
        words (re-seq #"[^,\s]+" text)
        longest-word (apply max 0 (map count words))
        longest-code (apply max 0 (map count code-spans))
        unbreakable-score (* 1.6 (max longest-word longest-code))
        content-score (min 80 (max 3 (count text)))
        code-bonus (if (seq code-spans) 1.15 1.0)]
    (* code-bonus (max content-score unbreakable-score))))

(defn- column-width-scores
  [rows]
  (let [headers (first rows)
        columns (apply map vector rows)]
    (mapv
     (fn [header column]
       (let [scores (map cell-width-score column)
             avg (/ (reduce + scores) (max 1 (count scores)))
             max-score (apply max scores)
             base (+ (* 0.7 avg) (* 0.3 max-score))
             kind (width-header-kind header)]
         (* base
            (case kind
              :prose 1.45
              :label 0.82
              1.0))))
     headers
     columns)))

(defn- column-min-width
  [n header]
  (let [kind (width-header-kind header)]
    (case n
      2 (case kind
          :prose 0.38
          :label 0.28
          0.24)
      3 (case kind
          :prose 0.28
          :label 0.24
          0.20)
      4 (case kind
          :prose 0.20
          :label 0.15
          0.13)
      (case kind
        :prose 0.16
        :label 0.10
        0.08))))

(defn- cell-unbreakable-length
  [cell]
  (let [code-spans (inline-code-spans cell)
        tokens (if (seq code-spans)
                 code-spans
                 (re-seq #"[^,\s]+" (markdown-width-text cell)))]
    (apply max 0 (map count tokens))))

(defn- column-content-min-width
  [n column]
  (let [longest (apply max 0 (map cell-unbreakable-length column))]
    (case n
      2 (cond
          (>= longest 30) 0.42
          (>= longest 24) 0.36
          (>= longest 18) 0.30
          :else 0.0)
      3 (cond
          (>= longest 24) 0.38
          (>= longest 20) 0.32
          (>= longest 16) 0.28
          (>= longest 12) 0.24
          :else 0.0)
      4 (cond
          (>= longest 24) 0.28
          (>= longest 20) 0.24
          (>= longest 16) 0.20
          (>= longest 12) 0.16
          :else 0.0)
      0.0)))

(defn- column-max-width
  [n header]
  (let [kind (width-header-kind header)]
    (case n
      2 (case kind
          :prose 0.72
          :label 0.42
          0.60)
      3 (case kind
          :prose 0.52
          :label 0.42
          0.46)
      4 (case kind
          :prose 0.42
          :label 0.34
          0.36)
      (case kind
        :prose 0.40
        :label 0.30
        0.34))))

(defn- distribute-table-widths
  [initial mins maxes]
  (let [n (count initial)
        min-total (reduce + mins)]
    (if (>= min-total 0.98)
      (mapv #(/ % min-total) mins)
      (loop [widths (vec mins)
             remaining (- 1.0 min-total)
             active (set (range n))]
        (if (or (< (Math/abs remaining) 0.0001) (empty? active))
          (let [diff (- 1.0 (reduce + widths))]
            (if (< (Math/abs diff) 0.0001)
              widths
              (update widths (dec n) + diff)))
          (let [weights (mapv #(max 0.001 (- (initial %) (mins %))) active)
                total (reduce + weights)
                result (reduce
                        (fn [{:keys [widths remaining active]} [idx weight]]
                          (let [share (* remaining (/ weight total))
                                capacity (- (maxes idx) (widths idx))
                                add (min share capacity)]
                            {:widths (update widths idx + add)
                             :remaining (- remaining add)
                             :active (if (< (- capacity add) 0.0001)
                                       (disj active idx)
                                       active)}))
                        {:widths widths
                         :remaining remaining
                         :active active}
                        (map vector active weights))]
            (recur (:widths result)
                   (:remaining result)
                   (:active result))))))))

(defn- normalize-column-widths
  [headers rows scores]
  (let [n (count scores)
        total (double (max 1.0 (reduce + scores)))
        initial (mapv #(/ % total) scores)
        columns (apply mapv vector rows)
        mins (mapv #(max (column-min-width n %1)
                         (column-content-min-width n %2))
                   headers
                   columns)
        maxes (mapv #(max (column-max-width n %1)
                          (column-content-min-width n %2))
                    headers
                    columns)]
    (distribute-table-widths initial mins maxes)))

(defn- column-dash-widths
  [rows]
  (let [headers (first rows)
        widths (normalize-column-widths headers rows (column-width-scores rows))
        dash-total (case (count widths)
                     2 40
                     3 54
                     4 64
                     72)
        raw (mapv #(max 3 (int (Math/round (* dash-total %)))) widths)
        diff (- dash-total (reduce + raw))]
    (if (zero? diff)
      raw
      (update raw (dec (count raw)) + diff))))

(defn- rebalance-pipe-table
  [lines]
  (let [rows (mapv table-cells lines)
        header (first rows)
        separator (second rows)
        n (count header)
        dash-counts (mapv table-separator-dash-count separator)]
    (if (and (<= 2 n 4)
             (every? #(= n (count %)) rows)
             (apply = dash-counts))
      (let [widths (column-dash-widths (remove table-separator-row? rows))
            new-separator (str "| "
                               (str/join " | "
                                         (map separator-cell separator widths))
                               " |")]
        (assoc lines 1 new-separator))
      lines)))

(defn- table-start?
  [line next-line]
  (and (table-cells line)
       (some-> next-line table-cells table-separator-row?)))

(defn- fit-pipe-table-widths
  [markdown]
  (let [lines (vec (str/split-lines markdown))]
    (loop [idx 0
           out []
           in-code? false]
      (if (>= idx (count lines))
        (str (str/join "\n" out) "\n")
        (let [line (lines idx)]
          (cond
            (re-matches #"^\s*```.*$" line)
            (recur (inc idx) (conj out line) (not in-code?))

            (and (not in-code?)
                 (table-start? line (get lines (inc idx))))
            (let [table-lines (->> (subvec lines idx)
                                   (take-while table-cells)
                                   vec)]
              (recur (+ idx (count table-lines))
                     (into out (rebalance-pipe-table table-lines))
                     in-code?))

            :else
            (recur (inc idx) (conj out line) in-code?)))))))

(defn- inline-code-spans
  [markdown]
  (map second (re-seq #"`([^`\n]+)`" markdown)))

(defn- unqualify-function-name
  [s]
  (let [s (str/trim s)]
    (cond
      (= s "/") s
      (str/includes? s "/") (last (str/split s #"/"))
      :else s)))

(defn- function-name-token?
  [s]
  (let [s (str/trim s)]
    (and (not (str/blank? s))
         (not (contains? #{"true" "false" "nil"} s))
         (re-matches #"[-A-Za-z0-9*+!<>=?._/]+" s)
         (not (str/includes? s ":"))
         (not (str/includes? s "://"))
         (not (and (> (count s) 1)
                   (str/starts-with? s "*")
                   (str/ends-with? s "*")))
         (not (and (> (count s) 1)
                   (str/starts-with? s "+")
                   (str/ends-with? s "+")))
         (or (contains? function-operator-names s)
             (re-find #"[A-Za-z]" s)))))

(defn- function-table-names
  [markdown]
  (:names
   (reduce
    (fn [{:keys [in-function-table?] :as state} line]
      (if-let [cells (table-cells line)]
        (let [first-cell (-> cells first str/lower-case)]
          (cond
            (table-separator-row? cells)
            state

            (= first-cell "function")
            (assoc state :in-function-table? true)

            in-function-table?
            (update state :names into
                    (->> (inline-code-spans (first cells))
                         (map unqualify-function-name)
                         (filter function-name-token?)))

            :else
            (assoc state :in-function-table? false)))
        (assoc state :in-function-table? false)))
    {:names [] :in-function-table? false}
    (str/split-lines markdown))))

(defn- qualified-function-symbols
  [markdown]
  (->> (re-seq #"(?<![:\p{L}\p{N}_./-])([A-Za-z][A-Za-z0-9_.-]*/[-A-Za-z0-9*+!<>=?._]+)" markdown)
       (map second)
       (filter (fn [sym]
                 (let [[qualifier function-name] (str/split sym #"/" 2)]
                   (and (not (str/includes? function-name "="))
                        (not (re-matches #"[A-Z0-9_]+" function-name))
                        (or (contains? #{"d" "cl" "i" "main" "srv" "str" "su" "u" "udf"}
                                       qualifier)
                       (str/starts-with? qualifier "datalevin.")
                       (str/starts-with? qualifier "clojure.")
                            (str/starts-with? qualifier "my-app."))))))
       (map unqualify-function-name)
       (filter function-name-token?)))

(defn- clojure-code-blocks
  [markdown]
  (let [{:keys [blocks current in-code? keep?]}
        (reduce
         (fn [{:keys [current in-code? keep?] :as state} line]
           (if in-code?
             (if (re-matches #"^\s*```\s*$" line)
               (cond-> (assoc state
                              :in-code? false
                              :keep? false
                              :current [])
                 keep? (update :blocks conj (str/join "\n" current)))
               (cond-> state
                 keep? (update :current conj line)))
             (if-let [[_ _ info] (re-matches #"^(\s*)```(.*)$" line)]
               (assoc state
                      :in-code? true
                      :keep? (clojure-fence? info)
                      :current [])
               state)))
         {:blocks [] :current [] :in-code? false :keep? false}
         (str/split-lines markdown))]
    (when in-code?
      (throw (ex-info "Unclosed fenced code block while extracting function names"
                      {})))
    blocks))

(defn- clojure-call-function-names
  [code reference-names]
  (->> (re-seq #"\(([-A-Za-z0-9*+!<>=?._/]+)" code)
       (map second)
       (map unqualify-function-name)
       (filter function-name-token?)
       (filter reference-names)))

(defn- inline-reference-function-names
  [markdown reference-names]
  (->> (inline-code-spans markdown)
       (map unqualify-function-name)
       (filter function-name-token?)
       (filter reference-names)))

(defn- prose-inline-function-name?
  [function-name]
  (or (>= (count function-name) 5)
      (re-find #"[-!?]" function-name)))

(defn- function-term-patterns
  [function-name]
  (let [quoted (Pattern/quote function-name)
        inline-pattern (str "`(?:" qualified-function-qualifier-pattern "/)?"
                            quoted
                            "`")
        qualified-pattern (str "(?<![:\\p{L}\\p{N}_./-])"
                               qualified-function-qualifier-pattern
                               "/"
                               quoted
                               "(?![\\p{L}\\p{N}_./-])")
        call-pattern (str "\\((?:" qualified-function-qualifier-pattern "/)?"
                          quoted
                          "(?=[\\s\\)\\]\\}\\n])")]
    {:patterns (cond-> [qualified-pattern]
                 (prose-inline-function-name? function-name)
                 (conj inline-pattern))
     :code-patterns [inline-pattern qualified-pattern call-pattern]}))

(defn- function-index-terms
  []
  (let [markdowns (map read-book-markdown (book-content-files))
        table-names (mapcat function-table-names markdowns)
        qualified-names (mapcat qualified-function-symbols markdowns)
        reference-names (set (concat extra-function-index-names
                                     table-names
                                     qualified-names))
        code-names (->> markdowns
                        (mapcat clojure-code-blocks)
                        (mapcat #(clojure-call-function-names %
                                                               reference-names)))
        inline-names (mapcat #(inline-reference-function-names %
                                                               reference-names)
                             markdowns)
        function-names (->> (concat reference-names code-names inline-names)
                            (filter function-name-token?)
                            set
                            sort)]
    (->> function-names
         (map (fn [function-name]
                (let [{:keys [patterns code-patterns]}
                      (function-term-patterns function-name)]
                  {:term function-name
                   :raw-index (function-index-entry function-name)
                   :case-sensitive? true
                   :patterns patterns
                   :code-patterns code-patterns})))
         (mapv compile-index-term))))

(defn- book-index-terms
  []
  (vec (concat (index-terms)
               (function-index-terms))))

(defn- chapter-number
  [file]
  (parse-long (subs (.getName file) 0 2)))

(defn- chapter-part
  [n]
  (cond
    (<= 1 n 5) "I"
    (<= 6 n 10) "II"
    (<= 11 n 14) "III"
    (<= 15 n 18) "IV"
    (<= 19 n 22) "V"
    (<= 23 n 27) "VI"
    :else "VII"))

(defn- part-heading
  [part]
  (str "\\part{" (latex-escape (part-titles part)) "}"))

(defn- final-markdown
  []
  (let [{:keys [note-lines] :as title} (title-metadata)
        terms (book-index-terms)
        copyright (prepare-markdown (jio/file docs-dir "copyright.md")
                                    normalize-front-heading)
        dedication (dedication-page (jio/file docs-dir "dedication.md"))
        preface (prepare-markdown (jio/file docs-dir "preface.md")
                                  normalize-preface-heading
                                  terms)
        chapters (chapter-files)]
    (str
     (pdf-metadata-block title)
     (raw-latex "\\frontmatter")
     (frontmatter-note note-lines)
     copyright
     "\n\\clearpage\n\n"
     dedication
     "\n\\clearpage\n\n"
     "\\bookmarksetup{startatroot}\n"
     "\\pdfbookmark[0]{Contents}{contents}\n"
     "\\setcounter{tocdepth}{1}\n"
     "\\tableofcontents\n"
     "\\bookmarksetup{startatroot}\n"
     "\n\\clearpage\n\n"
     (raw-latex "\\mainmatter")
     preface
     (loop [files chapters
            current-part nil
            out []]
       (if-let [file (first files)]
         (let [n (chapter-number file)
               part (chapter-part n)
               part-block (when (not= part current-part)
                            (str (when (= part "VII")
                                   (raw-latex "\\appendix"))
                                 (raw-latex (part-heading part))))
               chapter (prepare-markdown file normalize-chapter-heading terms)]
           (recur (rest files)
                  part
                  (cond-> out
                    part-block (conj part-block)
                    true (conj chapter))))
         (str (str/join "\n" out)
	              (raw-latex "\\backmatter
\\cleardoublepage
\\phantomsection
\\addcontentsline{toc}{chapter}{Index}
\\markboth{Index}{Index}
\\printindex
\\cleardoublepage")))))))

(defn- run-command!
  [failure-message cmd]
  (let [{:keys [exit out err]} (apply shell/sh cmd)]
    (when (seq out)
      (print out))
    (when-not (zero? exit)
      (when (seq err)
        (binding [*out* *err*]
          (print err)))
      (throw (ex-info failure-message
                      {:exit exit
                       :command cmd})))
    (when (seq err)
      (binding [*out* *err*]
        (print err)))))

(defn- svg-file?
  [file]
  (and (.isFile file)
       (str/ends-with? (.getName file) ".svg")))

(defn- relative-path
  [root file]
  (str (.relativize (.toPath (jio/file root))
                    (.toPath file))))

(defn- pdf-image-path
  [relative-svg-path]
  (str converted-image-dir "/"
       (str/replace relative-svg-path #"\.svg$" ".pdf")))

(def fonts-dir "resources/fonts")

(defn- absolute-path
  [path]
  (.getAbsolutePath (jio/file path)))

(defn- write-fonts-conf!
  "Write a fontconfig file that layers the bundled figure fonts (Inter and IBM
  Plex Mono in resources/fonts) on top of the system fonts, and return its
  absolute path. This makes figure text reproducible across build machines
  instead of depending on whatever sans/mono each host happens to install."
  []
  (let [conf-file (jio/file pdf-dir "fonts.conf")
        cache-dir (jio/file pdf-dir "fontcache")]
    (.mkdirs cache-dir)
    (jio/make-parents conf-file)
    (spit conf-file
          (str "<?xml version=\"1.0\"?>\n"
               "<!DOCTYPE fontconfig SYSTEM \"fonts.dtd\">\n"
               "<fontconfig>\n"
               "  <dir>" (absolute-path fonts-dir) "</dir>\n"
               "  <include ignore_missing=\"yes\">/opt/homebrew/etc/fonts/fonts.conf</include>\n"
               "  <include ignore_missing=\"yes\">/usr/local/etc/fonts/fonts.conf</include>\n"
               "  <include ignore_missing=\"yes\">/etc/fonts/fonts.conf</include>\n"
               "  <cachedir>" (.getAbsolutePath cache-dir) "</cachedir>\n"
               "</fontconfig>\n"))
    (.getAbsolutePath conf-file)))

(defn- font-render-env
  "Environment for rsvg-convert so figure text is rendered with the bundled
  fonts. PANGOCAIRO_BACKEND=fc forces the fontconfig/FreeType backend; without
  it, pango on macOS uses CoreText and ignores FONTCONFIG_FILE, falling back to
  whatever system fonts exist (Menlo, the UI font), which is the low-quality
  look we are fixing."
  [fonts-conf]
  (merge (into {} (System/getenv))
         {"PANGOCAIRO_BACKEND" "fc"
          "FONTCONFIG_FILE" fonts-conf
          "XDG_CACHE_HOME" (absolute-path (str pdf-dir "/fontcache"))}))

(defn- convert-svg-assets!
  []
  (let [fonts-conf (write-fonts-conf!)
        env (font-render-env fonts-conf)]
    (doseq [svg (->> (file-seq (jio/file "resources/public/images"))
                     (filter svg-file?))]
      (let [relative-svg-path (relative-path "resources/public/images" svg)
            output-file (pdf-image-path relative-svg-path)]
        (jio/make-parents output-file)
        (run-command! "SVG to PDF conversion failed"
                      ["rsvg-convert" "-f" "pdf"
                       "-o" output-file
                       (.getPath svg)
                       :env env])))))

(defn write-final-source
  "Generate the print-oriented final Markdown consumed by Pandoc."
  [_]
  (b/delete {:path pdf-dir})
  (jio/make-parents final-md-file)
  (spit final-md-file (final-markdown))
  (println "Wrote:" final-md-file))

(defn final-pdf
  "Build target/pdf/datalevin-definitive-guide.pdf with a TOC and index.

   The generated Markdown keeps Clojure fenced code blocks and command-line
   console fences. Other non-Clojure fenced blocks remain in the web book but
   are omitted from this PDF source."
  [_]
  (write-final-source nil)
  (convert-svg-assets!)
  (run-command! "Pandoc final TeX build failed"
                ["pandoc" final-md-file
                 "--from" "markdown+raw_tex+fenced_code_attributes"
                 "--standalone"
                 "--top-level-division=chapter"
                 "--resource-path=.:resources/public"
                 "-o" final-tex-file])
  (doseq [cmd [["xelatex" "-interaction=nonstopmode" "-halt-on-error"
                (str "-jobname=" final-pdf-stem)
                "-output-directory" pdf-dir
                final-tex-file]
               ["makeindex" (str pdf-dir "/" final-pdf-stem ".idx")]
               ["xelatex" "-interaction=nonstopmode" "-halt-on-error"
                (str "-jobname=" final-pdf-stem)
                "-output-directory" pdf-dir
                final-tex-file]
               ["xelatex" "-interaction=nonstopmode" "-halt-on-error"
                (str "-jobname=" final-pdf-stem)
                "-output-directory" pdf-dir
                final-tex-file]]]
    (run-command! "Final PDF build failed" cmd))
  (println "Built:" final-pdf-file))

(comment
  ;; Build a runnable uberjar.
  ;; clojure -T:build uber
  ;;
  ;; Build the final print-oriented PDF.
  ;; clojure -T:build final-pdf
  )
