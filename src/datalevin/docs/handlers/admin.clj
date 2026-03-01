(ns datalevin.docs.handlers.admin
  (:require [datalevin.core :as d]
            [datalevin.docs.handlers.pages :as pages]
            [datalevin.docs.views.layout :as layout]
            [clojure.java.io :as jio]
            [clojure.string :as str]
            [ring.middleware.anti-forgery :as anti-forgery]
            [taoensso.timbre :as log])
  (:import [java.util Date UUID]))

(def ^:private admin-example-pull-expr
  [:example/id :example/title :example/code :example/output
   :example/description :example/doc-section :example/created-at :example/removed?
   {:example/author [:user/id :user/username]}])

;; ---- Remove / Restore ----

(defn remove-example-handler [{:keys [path-params session biff.datalevin/conn] :as req}]
  (let [id-str (:id path-params)
        example-id (try (UUID/fromString id-str) (catch Exception _ nil))]
    (if example-id
      (do
        (d/transact! conn [{:db/id [:example/id example-id] :example/removed? true}])
        {:status 302
         :session (assoc session :flash {:success "Example removed"})
         :headers {"Location" (or (get-in req [:headers "referer"]) "/admin/examples")}})
      {:status 400 :body "Invalid example ID"})))

(defn restore-example-handler [{:keys [path-params session biff.datalevin/conn] :as req}]
  (let [id-str (:id path-params)
        example-id (try (UUID/fromString id-str) (catch Exception _ nil))]
    (if example-id
      (do
        (d/transact! conn [{:db/id [:example/id example-id] :example/removed? false}])
        {:status 302
         :session (assoc session :flash {:success "Example restored"})
         :headers {"Location" (or (get-in req [:headers "referer"]) "/admin/examples")}})
      {:status 400 :body "Invalid example ID"})))

;; ---- Admin examples listing ----

(defn- render-admin-example [ex token]
  (let [{:keys [example/id example/title example/code example/removed?
                example/doc-section example/created-at]} ex
        author-name (get-in ex [:example/author :user/username] "Anonymous")]
    [:div {:class (str "border rounded-lg p-4 "
                       (if removed? "bg-red-50 border-red-200" "bg-white border-gray-200"))}
     [:div {:class "flex items-start justify-between gap-4"}
      [:div {:class "min-w-0 flex-1"}
       [:div {:class "flex items-center gap-2 mb-1"}
        [:h3 {:class "font-semibold text-gray-900 truncate"} title]
        (when removed?
          [:span {:class "text-xs bg-red-100 text-red-700 px-2 py-0.5 rounded-full"} "Removed"])]
       [:pre {:class "bg-gray-900 text-gray-100 p-2 rounded text-xs font-mono overflow-x-auto mt-2 max-h-24"}
        [:code code]]
       [:div {:class "flex items-center gap-3 mt-2 text-xs text-gray-500"}
        [:span "by " [:a {:href (str "/users/" author-name) :class "text-blue-600 hover:underline"} author-name]]
        (when (seq doc-section)
          [:span "in " [:a {:href (str "/docs/" doc-section) :class "text-blue-600 hover:underline"} doc-section]])
        [:span (str created-at)]]]
      [:div {:class "flex-shrink-0"}
       (if removed?
         [:form {:method "post" :action (str "/admin/examples/" id "/restore")}
          [:input {:type "hidden" :name "__anti-forgery-token" :value token}]
          [:button {:type "submit" :class "text-sm px-3 py-1 rounded bg-green-600 text-white hover:bg-green-700"}
           "Restore"]]
         [:form {:method "post" :action (str "/admin/examples/" id "/remove")}
          [:input {:type "hidden" :name "__anti-forgery-token" :value token}]
          [:button {:type "submit" :class "text-sm px-3 py-1 rounded bg-red-600 text-white hover:bg-red-700"
                    :onclick "return confirm('Remove this example?')"}
           "Remove"]])]]]))

(defn admin-examples-handler [{:keys [biff.datalevin/conn params] :as req}]
  (let [db (d/db conn)
        filter-param (get params "filter" "all")
        all-examples (d/q `[:find [(~'pull ~'?e ~admin-example-pull-expr) ...]
                            :where [~'?e :example/id]]
                          db)
        filtered (case filter-param
                   "removed" (filter :example/removed? all-examples)
                   "active"  (remove :example/removed? all-examples)
                   all-examples)
        sorted (sort-by :example/created-at #(compare %2 %1) filtered)
        token (force anti-forgery/*anti-forgery-token*)
        active-count (count (remove :example/removed? all-examples))
        removed-count (count (filter :example/removed? all-examples))]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (layout/base-with-req "Admin — Examples" req
             [:div {:class "max-w-5xl mx-auto py-8 px-4"}
              [:div {:class "flex items-center justify-between mb-2"}
               [:h1 {:class "text-3xl font-bold text-gray-900"} "Manage Examples"]
               [:form {:method "post" :action "/admin/reindex"}
                [:input {:type "hidden" :name "__anti-forgery-token" :value token}]
                [:button {:type "submit" :class "px-3 py-1.5 rounded-lg text-sm font-medium bg-green-600 text-white hover:bg-green-700"}
                 "Reindex Search"]]]
              [:p {:class "text-sm text-gray-500 mb-6"}
               (str active-count " active, " removed-count " removed")]
              [:div {:class "flex gap-2 mb-6"}
               (for [[label val] [["All" "all"] ["Active" "active"] ["Removed" "removed"]]]
                 [:a {:href (str "/admin/examples?filter=" val)
                      :class (str "px-3 py-1.5 rounded-lg text-sm font-medium "
                                  (if (= filter-param val)
                                    "bg-blue-600 text-white"
                                    "bg-gray-100 text-gray-700 hover:bg-gray-200"))}
                  label])]
              (if (empty? sorted)
                [:p {:class "text-gray-500 py-8 text-center"} "No examples match this filter."]
                [:div {:class "space-y-4"}
                 (for [ex sorted]
                   (render-admin-example ex token))])])}))

(defn reindex-handler [{:keys [headers biff.datalevin/conn session] :as req}]
  (let [reindex-secret (:reindex-secret req)
        secret-header (get headers "x-reindex-secret")
        user (:user req)
        authorized? (or (= (:user/role user) :admin)
                        (and (seq reindex-secret) (= secret-header reindex-secret)))]
    (if-not authorized?
      {:status 403 :headers {"Content-Type" "text/html; charset=utf-8"} :body (layout/forbidden-page)}
      (try
        (let [docs-dir "resources/docs"
              files (->> (jio/file docs-dir)
                         (.listFiles)
                         (filter #(and (.isFile %) (str/ends-with? (.getName %) ".md")))
                         (remove #(= (.getName %) "toc.md")))]
          (doseq [f files]
            (let [filename (str/replace (.getName f) #"\.md$" "")
                  content (slurp f)
                  {:keys [frontmatter markdown]} (pages/parse-frontmatter content)
                  doc-tx (cond-> {:doc/filename filename
                                  :doc/content markdown
                                  :doc/updated-at (Date.)}
                           (:title frontmatter) (assoc :doc/title (:title frontmatter))
                           (:chapter frontmatter) (assoc :doc/chapter (:chapter frontmatter))
                           (:part frontmatter) (assoc :doc/part (:part frontmatter)))]
              (d/transact! conn [doc-tx])))
          ;; Clear caches after reindex
          (pages/clear-all-caches!)
          (log/info "Reindex complete:" (count files) "docs")
          {:status 302
           :session (assoc session :flash {:success (str "Reindexed " (count files) " documents")})
           :headers {"Location" "/admin/examples"}})
        (catch Exception e
          (log/error e "Reindex failed")
          {:status 500 :body "Reindex failed"})))))

(defn wrap-require-admin [handler]
  (fn [req]
    (if (= :admin (get-in req [:user :user/role]))
      (handler req)
      {:status 403 :headers {"Content-Type" "text/html; charset=utf-8"} :body (layout/forbidden-page)})))
