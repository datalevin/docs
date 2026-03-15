(ns datalevin.docs.handlers.search
  (:require [datalevin.docs.views.layout :as layout]
            [datalevin.core :as d]
            [clojure.string :as str]
            [hiccup2.core :as h]))

(def ^:private snippet-radius 60)

(defn- token-length-at
  "Returns the length of the word token starting at `pos` in `text`."
  [^String text ^long pos]
  (let [len (.length text)]
    (if (>= pos len)
      0
      (loop [i pos]
        (if (or (>= i len)
                (not (Character/isLetterOrDigit (.charAt text i))))
          (- i pos)
          (recur (inc i)))))))

(defn- merge-spans
  "Merge overlapping or adjacent highlight spans sorted by start position."
  [spans]
  (reduce (fn [acc [s e]]
            (if-let [[ps pe] (peek acc)]
              (if (<= s pe)
                (conj (pop acc) [ps (max pe e)])
                (conj acc [s e]))
              (conj acc [s e])))
          [] spans))

(defn- build-snippet
  "Build snippet content as Hiccup-safe strings and <mark> nodes.
   `text` is the source text; `offsets` is a seq of [term [pos ...]] from fulltext."
  [^String text offsets]
  (when (and (seq text) (seq offsets))
    (let [spans (->> offsets
                     (mapcat (fn [[_term positions]]
                               (keep (fn [pos]
                                       (when (< pos (.length text))
                                         (let [tlen (token-length-at text pos)]
                                           [pos (+ pos (max tlen 1))])))
                                     positions)))
                     (sort-by first)
                     merge-spans)
          [first-start _] (first spans)
          window-start (max 0 (- first-start snippet-radius))
          window-end   (min (.length text) (+ first-start (* 2 snippet-radius)))
          visible (->> spans
                       (keep (fn [[s e]]
                               (when (and (< s window-end) (> e window-start))
                                 [(max s window-start) (min e window-end)]))))]
      (loop [cursor window-start
             rem visible
             parts (cond-> []
                     (pos? window-start) (conj "…"))]
        (if (seq rem)
          (let [[s e] (first rem)]
            (recur e
                   (rest rem)
                   (cond-> parts
                     (< cursor s) (conj (subs text cursor s))
                     true (conj [:mark {:class "search-hit"} (subs text s e)]))))
          (cond-> parts
            (< cursor window-end) (conj (subs text cursor window-end))
            (< window-end (.length text)) (conj "…")))))))

(def ^:private max-query-length 200)

(defn- doc-result? [attr] (#{:doc/title :doc/content} attr))

(defn- result-for-doc [db entity attr offsets]
  (let [text (get (d/pull db [attr] (:db/id entity)) attr)]
    {:title    (:doc/title entity)
     :filename (:doc/filename entity)
     :chapter  (:doc/chapter entity)
     :snippet  (build-snippet text offsets)
     :type     :doc
     :url      (str "/docs/" (:doc/filename entity))}))

(defn- result-for-example [db entity attr offsets]
  (let [text (get (d/pull db [attr] (:db/id entity)) attr)]
    {:snippet     (build-snippet text offsets)
     :doc-section (:example/doc-section entity)
     :type        :example
     :url         (if-let [s (:example/doc-section entity)]
                    (str "/docs/" s)
                    "/examples")}))

(defn search [conn query]
  (when (and conn (seq query))
    (let [trimmed-query (str/trim query)
          q (subs trimmed-query 0 (min (count trimmed-query) max-query-length))
          db (d/db conn)]
      (when (seq q)
        (->> (d/q '[:find (pull ?e [:db/id :doc/title :doc/filename :doc/chapter
                                    :example/doc-section :example/removed?]) ?a ?offsets
                    :in $ ?q
                    :where [(fulltext $ ?q {:top 20 :display :offsets})
                            [[?e ?a _ ?offsets]]]]
                  db q)
             ;; Filter out removed examples
             (remove (fn [[entity _ _]] (:example/removed? entity)))
             ;; Group by entity; prefer :doc/content or :example/code for snippet
             (reduce (fn [m [entity a offsets]]
                       (let [eid  (:db/id entity)
                             prev (get m eid)]
                         (if (or (nil? prev)
                                 (and (not= (:attr prev) :doc/content)
                                      (= a :doc/content)))
                           (assoc m eid {:entity entity :attr a :offsets offsets})
                           m)))
                     {})
             vals
             (mapv (fn [{:keys [entity attr offsets]}]
                     (if (doc-result? attr)
                       (result-for-doc db entity attr offsets)
                       (result-for-example db entity attr offsets)))))))))

(defn render-result [r]
  (into
   [:a {:href (:url r)
        :class "search-result-card block p-4 rounded-lg transition"
        :style "background:var(--bg-card, rgba(255,255,255,0.05)); border:1px solid var(--border-card, rgba(255,255,255,0.1));"}]
   (case (:type r)
     :doc
     (concat
      [[:h3 {:class "text-lg font-medium"
             :style "color:var(--text-link, #22d3ee)"}
        (:title r)]]
      (when-let [snippet (:snippet r)]
        [(into [:p {:class "text-sm mt-1"
                    :style "color:var(--text-secondary, #9ca3af); line-height:1.5;"}]
               snippet)])
      [[:p {:class "text-xs mt-1"
            :style "color:var(--text-secondary, #6b7280)"}
        (or (:filename r) "")]])
     :example
     (concat
      [[:div {:class "flex items-center gap-2"}
        [:span {:class "text-xs px-2 py-0.5 rounded-full"
                :style "background:rgba(34,197,94,0.15); color:#86efac;"}
         "Example"]]]
      (when-let [snippet (:snippet r)]
        [(into [:p {:class "text-sm mt-1 font-mono"
                    :style "color:var(--text-secondary, #9ca3af); line-height:1.5;"}]
               snippet)])))))

(defn- no-results-view
  [query]
  (when (seq query)
    [:div {:class "text-center py-8 rounded-lg"
           :style "color:var(--text-secondary, #9ca3af); background:var(--bg-card, rgba(255,255,255,0.03)); border:1px solid var(--border-card, rgba(255,255,255,0.08));"}
     (if (seq (str query))
       (str "No results found for \"" query "\"")
       "No results found")]))

(defn search-all [req query]
  (search (:biff.datalevin/conn req) query))

(defn search-api-handler [{:keys [params] :as req}]
  (let [query (or (:q params) "")
        results (search-all req query)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (if (seq results)
             (str (h/html {:mode :html}
                          (for [r results]
                            (render-result r))))
             (some-> query no-results-view h/html str))}))

(defn search-page-handler [{:keys [params] :as req}]
  (let [query (or (:q params) "")
        results (search-all req query)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (layout/base-with-req "Search" req
               {:description "Search across Datalevin documentation and community examples."
                :canonical-path "/search"
                :robots "noindex,nofollow"}
               [:div {:class "max-w-2xl mx-auto py-8 px-4"}
                [:h1 {:class "text-3xl font-bold mb-6"
                      :style "color:var(--text-primary, #e5e7eb)"}
                 "Search Documentation"]
                [:div {:class "mb-6"}
                 [:input {:type "search"
                          :name "q"
                          :value query
                          :placeholder "Search docs and examples..."
                          :hx-get "/api/search"
                          :hx-target "#results"
                          :hx-trigger "keyup delay:300ms"
                          :autocomplete "off"
                          :class "w-full px-4 py-3 text-lg rounded-lg outline-none"
                          :style "background:var(--input-bg, rgba(255,255,255,0.05)); border:1px solid var(--input-border, rgba(255,255,255,0.1)); color:var(--text-primary, #e5e7eb);"}]]
                [:div {:id "results" :class "space-y-4"}
                 (when (seq query)
                   (if (seq results)
                     (for [r results]
                       (render-result r))
                     (no-results-view query)))]])}))
