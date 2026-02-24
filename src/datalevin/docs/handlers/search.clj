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
                         :where [(fulltext $ :doc/content ?q {:top 50}) [[?e ?a ?v]]]]
                       db q)]
      (mapv (fn [[e a v]]
              (let [doc (d/entity db e)]
                {:title (:doc/title doc)
                 :filename (:doc/filename doc)
                 :chapter (:doc/chapter doc)
                 :url (str "/docs/" (:doc/filename doc))}))
            (take 20 results)))))

(defn search-api-handler [{:keys [params] :as req}]
  (let [conn (:search/conn req)
        query (or (:q params) "")
        results (search-docs conn query)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (if (seq results)
             (apply str (map (fn [r] (str "<a class=\"block p-4 bg-white rounded-lg border border-gray-200 hover:border-blue-300\" href=\""
                                          (util/escape-html (:url r))
                                          "\"><h3 class=\"text-lg font-medium text-blue-600\">"
                                          (util/escape-html (:title r))
                                          "</h3><p class=\"text-sm text-gray-500\">"
                                          (util/escape-html (:filename r))
                                          "</p></a>"))
                             results))
             (when (seq query) "<div class=\"text-center py-8 text-gray-500\">No results found</div>"))}))

(defn search-page-handler [{:keys [params] :as req}]
  (let [conn (:search/conn req)
        query (:q params "")
        results (search-docs conn query)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (layout/base-with-req "Search" req
               [:div {:class "max-w-2xl mx-auto py-8 px-4"}
                [:h1 {:class "text-3xl font-bold text-gray-900 mb-6"} "Search Documentation"]
                [:div {:class "mb-6"}
                 [:input {:type "search" :name "q" :value query :placeholder "Search docs..."
                          :hx-get "/api/search" :hx-target "#results" :hx-trigger "keyup delay:300ms" :autocomplete "off"
                          :class "w-full px-4 py-3 text-lg border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"}]]
                [:div {:id "results" :class "space-y-4"}
                 (when (seq query)
                   (if (seq results)
                     (for [r results]
                       [:a {:href (:url r) :class "block p-4 bg-white rounded-lg border border-gray-200 hover:border-blue-300 hover:shadow-sm transition"}
                        [:h3 {:class "text-lg font-medium text-blue-600"} (:title r)]
                        [:p {:class "text-sm text-gray-500"} (:filename r)]])
                     [:div {:class "text-center py-8 text-gray-500 bg-white rounded-lg border border-gray-200"}
                      "No results found for \"" query "\""]))]])}))
