(ns datalevin.docs.routes
  (:require [biff.datalevin.middleware :as biff.mw]
            [datalevin.docs.handlers.pages :as pages]
            [datalevin.docs.handlers.search :as search]
            [datalevin.docs.handlers.auth :as auth]
            [datalevin.docs.handlers.examples :as examples]
            [datalevin.docs.tasks.pdf :as pdf]
            [datalevin.docs.views.layout :as layout]
            [reitit.ring :as ring]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.session :as ring-session]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.anti-forgery :as anti-forgery]))

(def router-opts {})

(defn wrap-auth [handler]
  (fn [req]
    (if (:user req)
      (handler req)
      {:status 302
       :headers {"Location" "/auth/login"}
       :session (assoc (:session req) :flash {:error "Please log in to continue"})})))

(defn login-page [req]
  (let [token (force anti-forgery/*anti-forgery-token*)
        flash (:flash (:session req))
        error-msg (:error flash)]
    {:status 200 
     :headers {"Content-Type" "text/html"}
     :body (layout/base "Login" 
              [:div
               [:h2 "Welcome back"]
               (when error-msg [:p {:style "color:red"} error-msg])
               [:form {:method "post" :action "/auth/login"}
                [:input {:type "hidden" :name "__anti-forgery-token" :value token}]
                [:div [:label "Email"] [:input {:type "email" :name "email" :required true}]]
                [:div [:label "Password"] [:input {:type "password" :name "password" :required true}]]
                [:button "Log in"]]
               [:p "Don't have an account? " [:a {:href "/auth/register"} "Sign up"]]])})) 

(defn register-page [req]
  (let [token (force anti-forgery/*anti-forgery-token*)
        flash (:flash (:session req))
        error-msg (:error flash)]
    {:status 200 
     :headers {"Content-Type" "text/html"}
     :body (layout/base "Sign Up"
              [:div
               [:h2 "Create your account"]
               (when error-msg [:p {:style "color:red"} error-msg])
               [:form {:method "post" :action "/auth/register"}
                [:input {:type "hidden" :name "__anti-forgery-token" :value token}]
                [:div [:label "Email"] [:input {:type "email" :name "email" :required true}]]
                [:div [:label "Username"] [:input {:type "text" :name "username" :required true}]]
                [:div [:label "Password"] [:input {:type "password" :name "password" :minLength 8 :required true}]]
                [:div [:label "Confirm Password"] [:input {:type "password" :name "confirm-password" :required true}]]
                [:button "Create account"]]
               [:p "Already have an account? " [:a {:href "/auth/login"} "Log in"]]])})) 

(defn new-example-page [req]
  (if (:user req)
    (examples/new-example-form req)
    {:status 302
     :headers {"Location" "/auth/login"}
     :session (assoc (:session req) :flash {:error "Please log in"})})) 

(defn app [sys]
  (let [handler (ring/ring-handler
                  (ring/router
                    [;; Public routes
                     ["/" {:get {:handler pages/home}}]
                     ["/docs" {:get {:handler pages/docs-index}}]
                     ["/docs/:chapter" {:get {:handler pages/doc-page}}]
                     ["/chapters/:chapter" {:get {:handler pages/doc-page}}]
                     
                     ;; Search
                     ["/search" {:get {:handler search/search-page-handler}}]
                     ["/api/search" {:get {:handler search/search-api-handler}}]
                     
                     ;; Auth
                     ["/auth/login" {:get {:handler login-page} :post {:handler auth/login-handler}}]
                     ["/auth/register" {:get {:handler register-page} :post {:handler auth/register-handler}}]
                     ["/auth/logout" {:get {:handler auth/logout-handler}}]
                     
                     ;; Protected routes
                     ["/examples/new" {:get {:handler new-example-page}}]
                     ["/examples" {:post {:handler (wrap-auth examples/create-example-handler)}}]
                     ["/pdf" {:get {:handler (wrap-auth pdf/pdf-handler)}}]
                     
                     ;; Health
                     ["/health" {:get {:handler (constantly {:status 200 :body "OK"})}}]]
                    router-opts)
                  (ring/create-default-handler
                    {:not-found (constantly {:status 404 :body "Not found"})}))]
    (-> handler
        (wrap-file "resources/public")
        (wrap-resource "public")
        (biff.mw/wrap-context sys)
        wrap-cookies
        (ring-session/wrap-session {:cookie-attrs {:max-age 86400}})
        (defaults/wrap-defaults defaults/site-defaults))))
