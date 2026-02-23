(ns datalevin.docs.handlers.search
  (:require [datalevin.docs.views.layout :as layout]
            [datalevin.core :as d]
            [clojure.string :as str]))

(defn search-docs [conn query]
  (when (and conn (seq (str/trim query)))
    (let [q (str/lower-case query)
          db (d/db conn)
          results (d/q '[:find ?title ?filename ?chapter
                        :where
                        [?e :doc/title ?title]
                        [?e :doc/filename ?filename]
                        [?e :doc/chapter ?chapter]]
                      db)]
      (mapv (fn [[title filename chapter]]
              {:title title :filename filename :chapter chapter :url (str "/docs/" filename)})
            (take 20 (filter (fn [[t _ _]] (some #(when (.contains (str/lower-case t) %) %) [q])) results))))))

(defn search-api-handler [{:keys [params] :as req}]
  (let [conn (:search/conn req)
        query (or (:q params) "")
        results (search-docs conn query)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (if (seq results)
             (apply str (map (fn [r] (str "<a class=\"block p-4 bg-white rounded-lg border border-gray-200 hover:border-blue-300\" href=\"" (:url r) "\"><h3 class=\"text-lg font-medium text-blue-600\">" (:title r) "</h3><p class=\"text-sm text-gray-500\">" (:filename r) "</p></a>")) results))
             (when (seq query) "<div class=\"text-center py-8 text-gray-500\">No results found</div>"))}))

(defn search-page-handler [{:keys [params] :as req}]
  (let [conn (:search/conn req)
        query (:q params "")
        results (search-docs conn query)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (layout/base-with-req "Search" req
               [:div
                [:h1 "Search Documentation"]
                [:form {:hx-get "/api/search" :hx-target "#results" :hx-trigger "submit" :action "/search" :method "get"}
                 [:input {:type "search" :name "q" :value query :placeholder "Search docs..."
                          :hx-get "/api/search" :hx-target "#results" :hx-trigger "keyup delay:300ms" :autocomplete "off"}]
                 [:button {:type "submit"} "Search"]]
                [:div {:id "results"}
                 (when (seq query)
                   (if (seq results)
                     (for [r results]
                       [:a {:href (:url r)} [:h3 (:title r)] [:p (:filename r)]])
                     [:p "No results found for \"" query "\""]))]])}))
