(ns datalevin.docs.handlers.search
  (:require [datalevin.docs.views.layout :as layout]
            [datalevin.docs.util :as util]
            [datalevin.core :as d]
            [clojure.string :as str]
            [hiccup2.core :as h]))

(def ^:private snippet-radius 60)

(defn- extract-snippet
  "Given content text and a query string, finds the best matching region
   and returns a snippet with the matched terms wrapped in <mark> tags."
  [content query]
  (when (and (seq content) (seq query))
    (let [terms (->> (str/split (str/lower-case query) #"\s+")
                     (remove #(< (count %) 2)))
          lower (str/lower-case content)
          ;; Find the position of the first matching term
          positions (for [t terms
                          :let [idx (str/index-of lower t)]
                          :when idx]
                      [idx t])
          best (first (sort-by first positions))]
      (when best
        (let [[pos _] best
              start (max 0 (- pos snippet-radius))
              end (min (count content) (+ pos snippet-radius))
              raw (subs content start end)
              ;; Add ellipsis
              raw (str (when (pos? start) "...") raw (when (< end (count content)) "..."))
              ;; Highlight all terms (case-insensitive replace with <mark>)
              highlighted (reduce
                            (fn [text term]
                              (str/replace text
                                           (re-pattern (str "(?i)" (java.util.regex.Pattern/quote term)))
                                           (fn [match] (str "<mark class=\"search-hit\">" (util/escape-html match) "</mark>"))))
                            (util/escape-html raw)
                            terms)]
          highlighted)))))

(defn search-docs [conn query]
  (when (and conn (seq query))
    (let [q (str/trim query)
          db (d/db conn)]
      (when (seq q)
        (->> (d/q '[:find [(pull ?e [:doc/title :doc/filename :doc/chapter :doc/content]) ...]
                    :in $ ?q
                    :where [(fulltext $ ?q {:top 20}) [[?e]]]]
                  db q)
             (mapv (fn [doc]
                     {:title (:doc/title doc)
                      :filename (:doc/filename doc)
                      :chapter (:doc/chapter doc)
                      :snippet (extract-snippet (:doc/content doc) q)
                      :type :doc
                      :url (str "/docs/" (:doc/filename doc))})))))))

(defn search-examples [conn query]
  (when (and conn (seq query))
    (let [q (str/trim query)
          db (d/db conn)]
      (when (seq q)
        (->> (d/q '[:find [(pull ?e [:example/title :example/code :example/description :example/doc-section]) ...]
                    :in $ ?q
                    :where [(fulltext $ ?q {:top 10}) [[?e]]]
                           [?e :example/removed? false]]
                  db q)
             (mapv (fn [ex]
                     {:title (:example/title ex)
                      :description (:example/description ex)
                      :snippet (extract-snippet (or (:example/code ex) (:example/description ex)) q)
                      :doc-section (:example/doc-section ex)
                      :type :example
                      :url (if-let [s (:example/doc-section ex)]
                             (str "/docs/" s)
                             "/examples")})))))))

(defn render-result [r]
  (str "<a class=\"block p-4 rounded-lg transition\" style=\"background:var(--bg-card, rgba(255,255,255,0.05)); border:1px solid var(--border-card, rgba(255,255,255,0.1));\" onmouseover=\"this.style.borderColor='rgba(6,182,212,0.4)';this.style.boxShadow='0 0 16px rgba(6,182,212,0.12)'\" onmouseout=\"this.style.borderColor='var(--border-card, rgba(255,255,255,0.1))';this.style.boxShadow='none'\" href=\""
       (util/escape-html (:url r))
       "\">"
       (case (:type r)
         :doc (str "<h3 class=\"text-lg font-medium\" style=\"color:var(--text-link, #22d3ee)\">"
                   (util/escape-html (:title r))
                   "</h3>"
                   (when (:snippet r)
                     (str "<p class=\"text-sm mt-1\" style=\"color:var(--text-secondary, #9ca3af); line-height:1.5;\">"
                          (:snippet r)
                          "</p>"))
                   "<p class=\"text-xs mt-1\" style=\"color:var(--text-secondary, #6b7280)\">"
                   (util/escape-html (or (:filename r) ""))
                   "</p>")
         :example (str "<div class=\"flex items-center gap-2\">"
                       "<span class=\"text-xs px-2 py-0.5 rounded-full\" style=\"background:rgba(34,197,94,0.15); color:#86efac;\">Example</span>"
                       "<h3 class=\"text-lg font-medium\" style=\"color:var(--text-link, #22d3ee)\">"
                       (util/escape-html (or (:title r) "Code example"))
                       "</h3></div>"
                       (when (:snippet r)
                         (str "<p class=\"text-sm mt-1 font-mono\" style=\"color:var(--text-secondary, #9ca3af); line-height:1.5;\">"
                              (:snippet r)
                              "</p>"))))
       "</a>"))

(defn search-all [req query]
  (let [conn (:biff.datalevin/conn req)
        doc-results (search-docs conn query)
        example-results (search-examples conn query)]
    (into (vec doc-results) example-results)))

(defn search-api-handler [{:keys [params] :as req}]
  (let [query (or (:q params) "")
        results (search-all req query)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (if (seq results)
             (apply str (map render-result results))
             (when (seq query) "<div class=\"text-center py-8\" style=\"color:var(--text-secondary, #9ca3af); background:var(--bg-card, rgba(255,255,255,0.03)); border:1px solid var(--border-card, rgba(255,255,255,0.08)); border-radius:0.5rem;\">No results found</div>"))}))

(defn search-page-handler [{:keys [params] :as req}]
  (let [query (or (:q params) "")
        results (search-all req query)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (layout/base-with-req "Search" req
               [:div {:class "max-w-2xl mx-auto py-8 px-4"}
                [:h1 {:class "text-3xl font-bold mb-6" :style "color:var(--text-primary, #e5e7eb)"} "Search Documentation"]
                [:div {:class "mb-6"}
                 [:input {:type "search" :name "q" :value query :placeholder "Search docs and examples..."
                          :hx-get "/api/search" :hx-target "#results" :hx-trigger "keyup delay:300ms" :autocomplete "off"
                          :class "w-full px-4 py-3 text-lg rounded-lg outline-none"
                          :style "background:var(--input-bg, rgba(255,255,255,0.05)); border:1px solid var(--input-border, rgba(255,255,255,0.1)); color:var(--text-primary, #e5e7eb);"}]]
                [:div {:id "results" :class "space-y-4"}
                 (when (seq query)
                   (if (seq results)
                     (for [r results]
                       [:a {:href (:url r)
                            :class "block p-4 rounded-lg transition"
                            :style "background:var(--bg-card, rgba(255,255,255,0.05)); border:1px solid var(--border-card, rgba(255,255,255,0.1));"}
                        (when (= :example (:type r))
                          [:span {:class "text-xs px-2 py-0.5 rounded-full mr-2"
                                  :style "background:rgba(34,197,94,0.15); color:#86efac;"} "Example"])
                        [:h3 {:class "text-lg font-medium inline" :style "color:var(--text-link, #22d3ee)"} (:title r)]
                        (when (:snippet r)
                          [:p {:class "text-sm mt-1" :style "color:var(--text-secondary, #9ca3af); line-height:1.5;"}
                           (h/raw (:snippet r))])
                        [:p {:class "text-xs mt-1" :style "color:var(--text-secondary, #6b7280)"} (or (:filename r) (:description r) "")]])
                     [:div {:class "text-center py-8 rounded-lg"
                            :style "color:var(--text-secondary, #9ca3af); background:var(--bg-card, rgba(255,255,255,0.03)); border:1px solid var(--border-card, rgba(255,255,255,0.08));"}
                      "No results found for \"" query "\""]))]])}))
