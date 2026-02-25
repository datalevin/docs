(ns datalevin.docs.handlers.examples
  (:require [datalevin.core :as d]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [ring.middleware.anti-forgery :as anti-forgery]
            [datalevin.docs.views.layout :as layout]
            [datalevin.docs.util :as util])
  (:import [java.util Date UUID]))

(def ^:private example-pull-expr
  [:example/id :example/code :example/doc-section :example/created-at
   {:example/author [:user/id :user/username :user/avatar-url]}])

(defn create-example-handler [{:keys [params session biff.datalevin/conn] :as req}]
  (let [user (:user session)
        code (str/trim (get params "code" ""))
        doc-section (get params "doc-section" "")]
    (cond
      (not user)
      {:status 302 :headers {"Location" "/auth/login"} :session (assoc session :flash {:error "Please log in"})}
      (empty? code)
      {:status 302 :headers {"Location" (if (seq doc-section) (str "/docs/" doc-section) "/examples/new")}
       :session (assoc session :flash {:error "Code required"})}
      :else
      (let [example-tx {:example/id (UUID/randomUUID)
                        :example/code (util/escape-html code)
                        :example/doc-section doc-section
                        :example/removed? false
                        :example/author [:user/id (:user/id user)]
                        :example/created-at (Date.)}]
        (d/transact! conn [example-tx])
        {:status 302 :headers {"Location" (str "/docs/" doc-section "#examples")}
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
                                   [:h1 {:class "text-3xl font-bold text-white"} "Community Examples"]
                                   [:a {:href "/examples/new"
                                        :class "text-white px-4 py-2 rounded-lg text-sm font-medium"
                                        :style "background:linear-gradient(135deg,#06b6d4,#3b82f6);"}
                                    "Add Example"]]
                                  (if (seq sorted)
                                    [:div {:class "space-y-6"}
                                     (for [ex sorted]
                                       (layout/render-example ex req))]
                                    [:div {:class "text-center py-16 text-gray-500"}
                                     [:p {:class "text-lg"} "No examples yet."]
                                     [:p {:class "text-sm mt-2"} "Be the first to "
                                      [:a {:href "/examples/new" :class "text-cyan-400 hover:text-cyan-300"} "submit one"] "!"]])])}))

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
          (let [doc-section (:example/doc-section example)]
            {:status 200
             :headers {"Content-Type" "text/html"}
             :body (layout/base-with-req "Example" req
                                         [:div {:class "max-w-4xl mx-auto py-8 px-4"}
                                          [:nav {:class "text-sm mb-4"}
                                           [:a {:href "/" :class "text-cyan-400 hover:text-cyan-300"} "Home"]
                                           [:span {:class "mx-2 text-gray-500"} "/"]
                                           [:a {:href "/examples" :class "text-cyan-400 hover:text-cyan-300"} "Examples"]]
                                          (layout/render-example example req)
                                          (when (seq doc-section)
                                            [:p {:class "mt-4 text-sm text-gray-500"}
                                             "Related doc: "
                                             [:a {:href (str "/docs/" doc-section) :class "text-cyan-400 hover:text-cyan-300"} doc-section]])])}))))))

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
                                        [:h1 {:class "text-2xl font-bold text-white"} username]
                                        (when-let [created (:user/created-at user)]
                                          [:p {:class "text-sm text-gray-500"} "Joined " (str created)])]]
                                      [:h2 {:class "text-xl font-semibold text-gray-200 mb-4"}
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
                         [:h1 {:class "text-3xl font-bold text-white mb-6"} "Submit Example"]
                         (when error-msg [:p {:class "p-3 rounded-lg mb-4 text-sm"
                                              :style "background:rgba(220,38,38,0.15); border:1px solid rgba(220,38,38,0.3); color:#fca5a5;"} error-msg])
                         (when success-msg [:p {:class "p-3 rounded-lg mb-4 text-sm"
                                                :style "background:rgba(34,197,94,0.15); border:1px solid rgba(34,197,94,0.3); color:#86efac;"} success-msg])
                         [:form {:method "post" :action "/examples" :class "space-y-4"}
                          [:input {:type "hidden" :name "__anti-forgery-token" :value token}]
                          [:input {:type "hidden" :name "doc-section" :value doc-section}]
                          [:textarea {:name "code" :required true :rows 10
                                      :placeholder "Paste your code example here\n;; Add comments to describe it"
                                      :class "w-full px-3 py-2 rounded-lg outline-none font-mono text-sm text-white"
                                      :style "background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.1);"}]
                          [:button {:type "submit"
                                    :class "w-full py-2.5 text-white rounded-lg font-medium transition"
                                    :style "background:linear-gradient(135deg,#06b6d4,#3b82f6);"} "Submit Example"]]])}))

(defn new-example-form-fragment [{:keys [params session] :as req}]
  (let [doc-section (get params "doc-section" "")
        token (force anti-forgery/*anti-forgery-token*)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (str
            (h/html
             [:form {:method "post" :action "/examples"
                     :class "p-4 rounded-lg"
                     :style "background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.1);"}
              [:input {:type "hidden" :name "__anti-forgery-token" :value token}]
              [:input {:type "hidden" :name "doc-section" :value doc-section}]
              [:textarea {:name "code" :required true :rows 8
                          :placeholder "Paste your code example here\n;; Add comments to describe it"
                          :class "w-full px-3 py-2 rounded-lg outline-none font-mono text-sm mb-3 text-white"
                          :style "background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.1);"}]
              [:div {:class "flex gap-3"}
               [:button {:type "submit"
                         :class "py-2 px-4 text-white rounded-lg font-medium"
                         :style "background:linear-gradient(135deg,#06b6d4,#3b82f6);"}
                "Submit"]
               [:button {:type "button"
                         :class "py-2 px-4 rounded-lg text-gray-300"
                         :style "border:1px solid rgba(255,255,255,0.15); background:rgba(255,255,255,0.05);"
                         :onclick "this.closest('form').remove()"}
                "Cancel"]]]))}))
