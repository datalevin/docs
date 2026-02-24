(ns datalevin.docs.handlers.examples
  (:require [datalevin.core :as d]
            [clojure.string :as str]
            [ring.middleware.anti-forgery :as anti-forgery]
            [datalevin.docs.views.layout :as layout]
            [datalevin.docs.util :as util])
  (:import [java.util Date UUID]))

(def ^:private example-pull-expr
  [:example/id :example/title :example/code :example/output
   :example/description :example/doc-section :example/created-at
   {:example/author [:user/id :user/username :user/avatar-url]}])

(defn create-example-handler [{:keys [params session biff.datalevin/conn] :as req}]
  (let [user (:user session)
        title (str/trim (get params "title" ""))
        code (str/trim (get params "code" ""))
        output (str/trim (get params "output" ""))
        doc-section (get params "doc-section" "")]
    (cond
      (not user)
      {:status 302 :headers {"Location" "/auth/login"} :session (assoc session :flash {:error "Please log in"})}
      (empty? title)
      {:status 302 :headers {"Location" "/examples/new"} :session (assoc session :flash {:error "Title required"})}
      (empty? code)
      {:status 302 :headers {"Location" "/examples/new"} :session (assoc session :flash {:error "Code required"})}
      :else
      (let [example-tx {:example/id (UUID/randomUUID)
                        :example/title (util/escape-html title)
                        :example/code (util/escape-html code)
                        :example/output (util/escape-html output)
                        :example/doc-section doc-section
                        :example/removed? false
                        :example/author [:user/id (:user/id user)] :example/created-at (Date.)}]
        (d/transact! conn [example-tx])
        {:status 302 :headers {"Location" (str "/docs/" doc-section)}
         :session (assoc session :flash {:success "Example submitted!"})}))))

;; ---- Browse all examples (GET /examples) ----

(defn list-examples-handler [{:keys [biff.datalevin/conn] :as req}]
  (let [db (d/db conn)
        examples (d/q `[:find [(~'pull ~'?e ~example-pull-expr) ...]
                        :where
                        [~'?e :example/id]
                        [~'?e :example/removed? false]]
                      db)
        sorted (sort-by :example/created-at #(compare %2 %1) examples)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (layout/base-with-req "Examples" req
             [:div {:class "max-w-4xl mx-auto py-8 px-4"}
              [:div {:class "flex items-center justify-between mb-6"}
               [:h1 {:class "text-3xl font-bold text-gray-900"} "Community Examples"]
               [:a {:href "/examples/new" :class "bg-blue-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-blue-700"}
                "Add Example"]]
              (if (seq sorted)
                [:div {:class "space-y-6"}
                 (for [ex sorted]
                   (layout/render-example ex req))]
                [:div {:class "text-center py-16 text-gray-500"}
                 [:p {:class "text-lg"} "No examples yet."]
                 [:p {:class "text-sm mt-2"} "Be the first to "
                  [:a {:href "/examples/new" :class "text-blue-600 hover:underline"} "submit one"] "!"]])])}))

;; ---- View single example (GET /examples/:id) ----

(defn view-example-handler [{:keys [path-params biff.datalevin/conn] :as req}]
  (let [id-str (:id path-params)
        example-id (try (UUID/fromString id-str) (catch Exception _ nil))]
    (if-not example-id
      {:status 404 :headers {"Content-Type" "text/html"}
       :body (layout/base-with-req "Not Found" req [:div {:class "max-w-4xl mx-auto py-16 text-center text-gray-500"} "Example not found"])}
      (let [db (d/db conn)
            example (d/q `[:find (~'pull ~'?e ~example-pull-expr) .
                           :in ~'$ ~'?id
                           :where
                           [~'?e :example/id ~'?id]
                           [~'?e :example/removed? false]]
                         db example-id)]
        (if-not example
          {:status 404 :headers {"Content-Type" "text/html"}
           :body (layout/base-with-req "Not Found" req [:div {:class "max-w-4xl mx-auto py-16 text-center text-gray-500"} "Example not found"])}
          (let [{:keys [example/title example/doc-section]} example]
            {:status 200
             :headers {"Content-Type" "text/html"}
             :body (layout/base-with-req (or title "Example") req
                     [:div {:class "max-w-4xl mx-auto py-8 px-4"}
                      [:nav {:class "text-sm mb-4"}
                       [:a {:href "/" :class "text-blue-600 hover:underline"} "Home"]
                       [:span {:class "mx-2 text-gray-400"} "/"]
                       [:a {:href "/examples" :class "text-blue-600 hover:underline"} "Examples"]
                       [:span {:class "mx-2 text-gray-400"} "/"]
                       [:span {:class "text-gray-600"} title]]
                      (layout/render-example example req)
                      (when (seq doc-section)
                        [:p {:class "mt-4 text-sm text-gray-500"}
                         "Related doc: "
                         [:a {:href (str "/docs/" doc-section) :class "text-blue-600 hover:underline"} doc-section]])])}))))))

;; ---- User profile (GET /users/:username) ----

(defn user-profile-handler [{:keys [path-params biff.datalevin/conn] :as req}]
  (let [username (:username path-params)
        db (d/db conn)
        user (d/q '[:find (pull ?u [:user/id :user/username :user/avatar-url :user/created-at]) .
                    :in $ ?username
                    :where [?u :user/username ?username]]
                  db username)]
    (if-not user
      {:status 404 :headers {"Content-Type" "text/html"}
       :body (layout/base-with-req "Not Found" req
               [:div {:class "max-w-4xl mx-auto py-16 text-center text-gray-500"} "User not found"])}
      (let [examples (d/q `[:find [(~'pull ~'?e ~example-pull-expr) ...]
                            :in ~'$ ~'?uid
                            :where
                            [~'?e :example/author ~'?uid]
                            [~'?e :example/removed? false]]
                          db (:db/id (d/entity db [:user/username username])))
            sorted (sort-by :example/created-at #(compare %2 %1) examples)]
        {:status 200
         :headers {"Content-Type" "text/html"}
         :body (layout/base-with-req (str username "'s Profile") req
                 [:div {:class "max-w-4xl mx-auto py-8 px-4"}
                  [:div {:class "flex items-center gap-4 mb-8"}
                   (when (:user/avatar-url user)
                     [:img {:src (:user/avatar-url user) :class "h-16 w-16 rounded-full"}])
                   [:div
                    [:h1 {:class "text-2xl font-bold text-gray-900"} username]
                    (when-let [created (:user/created-at user)]
                      [:p {:class "text-sm text-gray-500"} "Joined " (str created)])]]
                  [:h2 {:class "text-xl font-semibold text-gray-800 mb-4"}
                   "Examples (" (count sorted) ")"]
                  (if (seq sorted)
                    [:div {:class "space-y-6"}
                     (for [ex sorted]
                       (layout/render-example ex req))]
                    [:p {:class "text-gray-500"} "No examples yet."])])}))))

;; ---- New example form ----

(defn new-example-form [{:keys [params session] :as req}]
  (let [user (:user session)
        flash (:flash session)
        doc-section (get params "doc-section" "")
        token (force anti-forgery/*anti-forgery-token*)
        error-msg (:error flash)
        success-msg (:success flash)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (layout/base "Submit Example"
              [:div {:class "max-w-2xl mx-auto py-8 px-4"}
               [:h1 {:class "text-3xl font-bold text-gray-900 mb-6"} "Submit Example"]
               (when error-msg [:p {:class "bg-red-50 text-red-700 p-3 rounded-lg mb-4 text-sm"} error-msg])
               (when success-msg [:p {:class "bg-green-50 text-green-700 p-3 rounded-lg mb-4 text-sm"} success-msg])
               [:form {:method "post" :action "/examples" :class "space-y-4"}
                [:input {:type "hidden" :name "__anti-forgery-token" :value token}]
                [:input {:type "hidden" :name "doc-section" :value doc-section}]
                [:div
                 [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Title"]
                 [:input {:name "title" :required true :class "w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"}]]
                [:div
                 [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Code"]
                 [:textarea {:name "code" :required true :rows 8 :class "w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none font-mono text-sm"}]]
                [:div
                 [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Output (optional)"]
                 [:textarea {:name "output" :rows 4 :class "w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none font-mono text-sm"}]]
                [:button {:type "submit" :class "w-full py-2.5 bg-blue-600 text-white rounded-lg font-medium hover:bg-blue-700"} "Submit Example"]]])}))
