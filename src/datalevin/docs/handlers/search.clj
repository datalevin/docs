(ns datalevin.docs.handlers.search
  (:require [datalevin.docs.views.layout :as layout]
            [datalevin.docs.util :as util]
            [datalevin.core :as d]
            [clojure.string :as str]))

(defn search-docs [conn query]
  (when (and conn (seq (str/trim query)))
    (let [q (str/trim query)
          db (d/db conn)
          results (d/q '[:find ?e ?a ?v
                         :in $ ?q
                         :where [(fulltext $ ?q {:top 50}) [[?e ?a ?v]]]]
                       db q)]
      (->> results
           (take 20)
           (mapv (fn [[e a v]]
                   (let [doc (d/entity db e)]
                     {:title (:doc/title doc)
                      :filename (:doc/filename doc)
                      :chapter (:doc/chapter doc)
                      :type :doc
                      :url (str "/docs/" (:doc/filename doc))})))))))

(defn search-examples [conn query]
  (when (and conn (seq (str/trim query)))
    (let [q (str/trim query)
          db (d/db conn)
          results (d/q '[:find ?e ?a ?v
                         :in $ ?q
                         :where [(fulltext $ ?q {:top 20}) [[?e ?a ?v]]]
                                [?e :example/removed? false]]
                       db q)]
      (->> results
           (take 10)
           (mapv (fn [[e a v]]
                   (let [ex (d/entity db e)]
                     {:title (:example/title ex)
                      :description (:example/description ex)
                      :doc-section (:example/doc-section ex)
                      :type :example
                      :url (if-let [s (:example/doc-section ex)]
                             (str "/docs/" s)
                             "/examples")})))))))

(defn render-result [r]
  (str "<a class=\"block p-4 bg-white rounded-lg border border-gray-200 hover:border-blue-300 hover:shadow-sm transition\" href=\""
       (util/escape-html (:url r))
       "\">"
       (case (:type r)
         :doc (str "<h3 class=\"text-lg font-medium text-blue-600\">"
                   (util/escape-html (:title r))
                   "</h3><p class=\"text-sm text-gray-500\">"
                   (util/escape-html (or (:filename r) ""))
                   "</p>")
         :example (str "<div class=\"flex items-center gap-2\">"
                       "<span class=\"text-xs bg-green-100 text-green-700 px-2 py-0.5 rounded-full\">Example</span>"
                       "<h3 class=\"text-lg font-medium text-blue-600\">"
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
             (when (seq query) "<div class=\"text-center py-8 text-gray-500\">No results found</div>"))}))

(defn search-page-handler [{:keys [params] :as req}]
  (let [query (or (:q params) "")
        results (search-all req query)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (layout/base-with-req "Search" req
               [:div {:class "max-w-2xl mx-auto py-8 px-4"}
                [:h1 {:class "text-3xl font-bold text-gray-900 mb-6"} "Search Documentation"]
                [:div {:class "mb-6"}
                 [:input {:type "search" :name "q" :value query :placeholder "Search docs and examples..."
                          :hx-get "/api/search" :hx-target "#results" :hx-trigger "keyup delay:300ms" :autocomplete "off"
                          :class "w-full px-4 py-3 text-lg border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"}]]
                [:div {:id "results" :class "space-y-4"}
                 (when (seq query)
                   (if (seq results)
                     (for [r results]
                       [:a {:href (:url r) :class "block p-4 bg-white rounded-lg border border-gray-200 hover:border-blue-300 hover:shadow-sm transition"}
                        (when (= :example (:type r))
                          [:span {:class "text-xs bg-green-100 text-green-700 px-2 py-0.5 rounded-full mr-2"} "Example"])
                        [:h3 {:class "text-lg font-medium text-blue-600 inline"} (:title r)]
                        [:p {:class "text-sm text-gray-500"} (or (:filename r) (:description r) "")]])
                     [:div {:class "text-center py-8 text-gray-500 bg-white rounded-lg border border-gray-200"}
                      "No results found for \"" query "\""]))]])}))
