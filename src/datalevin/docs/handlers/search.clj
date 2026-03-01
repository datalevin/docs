(ns datalevin.docs.handlers.search
  (:require [datalevin.docs.views.layout :as layout]
            [datalevin.docs.util :as util]
            [datalevin.core :as d]
            [clojure.string :as str]))

(defn search-docs [conn query]
  (when (and conn (seq query))
    (let [q (clojure.string/trim query)
          db (d/db conn)]
      (if (empty? q)
        nil
        (let [results (d/q '[:find ?e ?a ?v
                             :in $ ?q
                             :where [(fulltext $ ?q {:top 50}) [[?e ?a ?v]]]]
                           db q)]
          (->> results
               (take 20)
               (mapv (fn [[e _a _v]]  ;; Don't need ?a ?v values
                       (let [doc (d/entity db e)]
                         {:title (:doc/title doc)
                          :filename (:doc/filename doc)
                          :chapter (:doc/chapter doc)
                          :type :doc
                          :url (str "/docs/" (:doc/filename doc))})))))))))

(defn search-examples [conn query]
  (when (and conn (seq query))
    (let [q (clojure.string/trim query)
          db (d/db conn)]
      (if (empty? q)
        nil
        (let [results (d/q '[:find ?e ?a ?v
                             :in $ ?q
                             :where [(fulltext $ ?q {:top 20}) [[?e ?a ?v]]]
                                    [?e :example/removed? false]]
                           db q)]
          (->> results
               (take 10)
               (mapv (fn [[e _a _v]]  ;; Don't need ?a ?v values
                       (let [ex (d/entity db e)]
                         {:title (:example/title ex)
                          :description (:example/description ex)
                          :doc-section (:example/doc-section ex)
                          :type :example
                          :url (if-let [s (:example/doc-section ex)]
                                 (str "/docs/" s)
                                 "/examples")})))))))))

(defn render-result [r]
  (str "<a class=\"block p-4 rounded-lg transition\" style=\"background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.1);\" onmouseover=\"this.style.borderColor='rgba(6,182,212,0.4)';this.style.boxShadow='0 0 16px rgba(6,182,212,0.12)'\" onmouseout=\"this.style.borderColor='rgba(255,255,255,0.1)';this.style.boxShadow='none'\" href=\""
       (util/escape-html (:url r))
       "\">"
       (case (:type r)
         :doc (str "<h3 class=\"text-lg font-medium text-cyan-400\">"
                   (util/escape-html (:title r))
                   "</h3><p class=\"text-sm text-gray-500\">"
                   (util/escape-html (or (:filename r) ""))
                   "</p>")
         :example (str "<div class=\"flex items-center gap-2\">"
                       "<span class=\"text-xs px-2 py-0.5 rounded-full\" style=\"background:rgba(34,197,94,0.15); color:#86efac;\">Example</span>"
                       "<h3 class=\"text-lg font-medium text-cyan-400\">"
                       (util/escape-html (:title r))
                       "</h3></div>"
                       (when (:description r)
                         (str "<p class=\"text-sm text-gray-500\">"
                              (util/escape-html (:description r))
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
             (when (seq query) "<div class=\"text-center py-8 text-gray-500\" style=\"background:rgba(255,255,255,0.03); border:1px solid rgba(255,255,255,0.08); border-radius:0.5rem;\">No results found</div>"))}))

(defn search-page-handler [{:keys [params] :as req}]
  (let [query (or (:q params) "")
        results (search-all req query)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (layout/base-with-req "Search" req
               [:div {:class "max-w-2xl mx-auto py-8 px-4"}
                [:h1 {:class "text-3xl font-bold text-white mb-6"} "Search Documentation"]
                [:div {:class "mb-6"}
                 [:input {:type "search" :name "q" :value query :placeholder "Search docs and examples..."
                          :hx-get "/api/search" :hx-target "#results" :hx-trigger "keyup delay:300ms" :autocomplete "off"
                          :class "w-full px-4 py-3 text-lg rounded-lg outline-none text-white"
                          :style "background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.1);"}]]
                [:div {:id "results" :class "space-y-4"}
                 (when (seq query)
                   (if (seq results)
                     (for [r results]
                       [:a {:href (:url r)
                            :class "block p-4 rounded-lg transition"
                            :style "background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.1);"}
                        (when (= :example (:type r))
                          [:span {:class "text-xs px-2 py-0.5 rounded-full mr-2"
                                  :style "background:rgba(34,197,94,0.15); color:#86efac;"} "Example"])
                        [:h3 {:class "text-lg font-medium text-cyan-400 inline"} (:title r)]
                        [:p {:class "text-sm text-gray-500"} (or (:filename r) (:description r) "")]])
                     [:div {:class "text-center py-8 text-gray-500 rounded-lg"
                            :style "background:rgba(255,255,255,0.03); border:1px solid rgba(255,255,255,0.08);"}
                      "No results found for \"" query "\""]))]])}))
