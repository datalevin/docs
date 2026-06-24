(ns datalevin.docs.handlers.search
  (:require [datalevin.docs.views.layout :as layout]
            [datalevin.docs.handlers.pages :as pages]
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

(def ^:private searchable-attrs
  #{:search/title :search/text :search/code :example/code})

(def ^:private attr-priority
  {:search/text 3
   :search/code 3
   :example/code 3
   :search/title 1})

(defn- hit-better?
  [new-attr old-attr]
  (> (get attr-priority new-attr 0)
     (get attr-priority old-attr 0)))

(defn- doc-url
  [doc anchor]
  (str "/docs/" doc (when (seq anchor) (str "#" anchor))))

(defn- result-for-search-record [db entity attr offsets]
  (let [text (get (d/pull db [attr] (:db/id entity)) attr)
        type (:search/type entity)]
    {:title    (or (:search/title entity)
                   (:search/context entity)
                   (:search/doc entity))
     :filename (:search/doc entity)
     :chapter  (:search/chapter entity)
     :part     (:search/part entity)
     :anchor   (:search/anchor entity)
     :language (:search/language entity)
     :snippet  (build-snippet text offsets)
     :type     type
     :url      (doc-url (:search/doc entity) (:search/anchor entity))}))

(defn- result-for-example [db entity attr offsets]
  (let [text (get (d/pull db [attr] (:db/id entity)) attr)]
    {:snippet     (build-snippet text offsets)
     :doc-section (:example/doc-section entity)
     :type        :community-example
     :url         (if-let [s (some-> (:example/doc-section entity) not-empty)]
                    (str "/docs/" s "#examples")
                    "/examples")}))

(defn- hidden-doc-result?
  [entity]
  (or (and (:search/doc entity)
           (not (pages/web-visible-slug? (:search/doc entity))))
      (and (seq (:example/doc-section entity))
           (not (pages/web-visible-slug? (:example/doc-section entity))))))

(def ^:private search-record-query
  '[:find (pull ?e [:db/id :search/type :search/doc :search/anchor
                    :search/title :search/context :search/language
                    :search/chapter :search/part])
          ?a ?offsets
    :in $ ?q
    :where [(fulltext $ ?q {:top 20
                            :display :offsets
                            :domains ["site" "code"]})
            [[?e ?a _ ?offsets]]]])

(def ^:private community-example-query
  '[:find (pull ?e [:db/id :example/doc-section :example/removed?]) ?a ?offsets
    :in $ ?q
    :where [(fulltext $ ?q {:top 20
                            :display :offsets
                            :domains ["datalevin"]})
            [[?e ?a _ ?offsets]]]
           [(= ?a :example/code)]])

(defn- collapse-hits
  [rows]
  (let [{:keys [order by-id]}
        (reduce (fn [{:keys [order by-id] :as state} [entity attr offsets]]
                  (if-not (searchable-attrs attr)
                    state
                    (let [eid (:db/id entity)
                          prev (get by-id eid)
                          hit {:entity entity :attr attr :offsets offsets}]
                      (if prev
                        (if (hit-better? attr (:attr prev))
                          (assoc-in state [:by-id eid] hit)
                          state)
                        {:order (conj order eid)
                         :by-id (assoc by-id eid hit)}))))
                {:order [] :by-id {}}
                rows)]
    (mapv by-id order)))

(defn search [conn query]
  (when (and conn (seq query))
    (let [trimmed-query (str/trim query)
          q (subs trimmed-query 0 (min (count trimmed-query) max-query-length))
          db (d/db conn)]
      (when (seq q)
        (->> (concat (d/q search-record-query db q)
                     (d/q community-example-query db q))
             ;; Filter out removed examples
             (remove (fn [[entity _ _]] (:example/removed? entity)))
             ;; Filter out print-only chapters and examples attached to them
             (remove (fn [[entity _ _]] (hidden-doc-result? entity)))
             collapse-hits
             (mapv (fn [{:keys [entity attr offsets]}]
                     (if (:search/type entity)
                       (result-for-search-record db entity attr offsets)
                       (result-for-example db entity attr offsets)))))))))

(defn- badge-label
  [r]
  (case (:type r)
    :section "Guide"
    :figure "Figure"
    :example (if-let [language (:language r)]
               (str "Code · " language)
               "Code")
    :community-example "Example"
    :doc nil
    nil))

(defn- result-meta
  [r]
  (case (:type r)
    :section (:filename r)
    :figure (:filename r)
    :example (:filename r)
    :community-example (:doc-section r)
    :doc (:filename r)
    (:filename r)))

(defn render-result [r]
  (into
   [:a {:href (:url r)
        :class "search-result-card block p-4 rounded-lg transition"
        :style "background:var(--bg-card, rgba(255,255,255,0.05)); border:1px solid var(--border-card, rgba(255,255,255,0.1));"}]
   (case (:type r)
     (:doc :section :figure)
     (concat
      (when-let [badge (badge-label r)]
        [[:div {:class "flex items-center gap-2 mb-1"}
          [:span {:class "text-xs px-2 py-0.5 rounded-full"
                  :style "background:rgba(34,211,238,0.15); color:#67e8f9;"}
           badge]]])
      [[:h3 {:class "text-lg font-medium"
             :style "color:var(--text-link, #22d3ee)"}
        (:title r)]]
      (when-let [snippet (:snippet r)]
        [(into [:p {:class "text-sm mt-1"
                    :style "color:var(--text-secondary, #9ca3af); line-height:1.5;"}]
               snippet)])
      [[:p {:class "text-xs mt-1"
            :style "color:var(--text-secondary, #6b7280)"}
        (or (result-meta r) "")]])
     (:example :community-example)
     (concat
      [[:div {:class "flex items-center gap-2"}
        [:span {:class "text-xs px-2 py-0.5 rounded-full"
                :style "background:rgba(34,197,94,0.15); color:#86efac;"}
         (or (badge-label r) "Example")]]]
      (when-let [title (:title r)]
        [[:h3 {:class "text-base font-medium mt-1"
               :style "color:var(--text-link, #22d3ee)"}
          title]])
      (when-let [snippet (:snippet r)]
        [(into [:p {:class "text-sm mt-1 font-mono"
                    :style "color:var(--text-secondary, #9ca3af); line-height:1.5;"}]
               snippet)])
      (when-let [meta (result-meta r)]
        [[:p {:class "text-xs mt-1"
              :style "color:var(--text-secondary, #6b7280)"}
          meta]])))))

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
