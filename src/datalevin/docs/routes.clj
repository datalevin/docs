(ns datalevin.docs.routes
  (:require [biff.datalevin.middleware :as biff.mw]
            [datalevin.docs.handlers.pages :as pages]
            [datalevin.docs.handlers.search :as search]
            [datalevin.docs.handlers.auth :as auth]
            [datalevin.docs.handlers.examples :as examples]
            [datalevin.docs.handlers.admin :as admin]
            [datalevin.docs.tasks.pdf :as pdf]
            [datalevin.docs.views.layout :as layout]
            [reitit.ring :as ring]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.session :as ring-session]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.anti-forgery :as anti-forgery]
            [datalevin.docs.middleware.rate-limit :as rate-limit]
            [taoensso.timbre :as log]))

(def router-opts {:conflicts nil})

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
        error-msg (:error flash)
        github-configured? (seq (:github-client-id req))]
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
               [:p [:a {:href "/auth/forgot-password"} "Forgot password?"]]
               (when github-configured?
                 [:div {:style "margin-top:1rem"}
                  [:a {:href "/auth/github" :class "inline-block bg-gray-800 text-white px-4 py-2 rounded"} "Sign in with GitHub"]])
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

(defn forgot-password-page [req]
  (let [token (force anti-forgery/*anti-forgery-token*)
        flash (:flash (:session req))
        error-msg (:error flash)
        success-msg (:success flash)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (layout/base "Forgot Password"
              [:div
               [:h2 "Reset your password"]
               (when error-msg [:p {:style "color:red"} error-msg])
               (when success-msg [:p {:style "color:green"} success-msg])
               [:form {:method "post" :action "/auth/forgot-password"}
                [:input {:type "hidden" :name "__anti-forgery-token" :value token}]
                [:div [:label "Email"] [:input {:type "email" :name "email" :required true}]]
                [:button "Send reset link"]]
               [:p [:a {:href "/auth/login"} "Back to login"]]])}))

(defn reset-password-page [req]
  (let [token-param (get-in req [:params "token"] "")
        csrf-token (force anti-forgery/*anti-forgery-token*)
        flash (:flash (:session req))
        error-msg (:error flash)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (layout/base "Reset Password"
              [:div
               [:h2 "Set new password"]
               (when error-msg [:p {:style "color:red"} error-msg])
               [:form {:method "post" :action "/auth/reset-password"}
                [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
                [:input {:type "hidden" :name "token" :value token-param}]
                [:div [:label "New Password"] [:input {:type "password" :name "password" :minLength 8 :required true}]]
                [:div [:label "Confirm Password"] [:input {:type "password" :name "confirm-password" :required true}]]
                [:button "Reset password"]]])}))

(defn new-example-page [req]
  (if (:user req)
    (examples/new-example-form req)
    {:status 302
     :headers {"Location" "/auth/login"}
     :session (assoc (:session req) :flash {:error "Please log in"})}))

(defn wrap-exceptions [handler]
  (fn [req]
    (try
      (handler req)
      (catch Exception e
        (log/error e "Unhandled exception on" (:request-method req) (:uri req))
        {:status 500
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (layout/server-error-page)}))))

(defn wrap-request-logging [handler]
  (fn [req]
    (let [start (System/nanoTime)
          resp (handler req)
          ms (/ (- (System/nanoTime) start) 1e6)]
      (log/debug (:request-method req) (:uri req) (:status resp) (format "%.1fms" ms))
      resp)))

(defn wrap-content-type [handler]
  (fn [req]
    (let [resp (handler req)]
      (if (and (string? (:body resp))
               (not (get-in resp [:headers "Content-Type"])))
        (assoc-in resp [:headers "Content-Type"] "text/html; charset=utf-8")
        resp))))

(defn wrap-security-headers [handler]
  (fn [req]
    (when-let [resp (handler req)]
      (update resp :headers merge
              {"X-Frame-Options" "DENY"
               "X-Content-Type-Options" "nosniff"
               "X-XSS-Protection" "1; mode=block"
               "Referrer-Policy" "strict-origin-when-cross-origin"
               "Permissions-Policy" "camera=(), microphone=(), geolocation=()"
               "Content-Security-Policy" (str "default-src 'self'; "
                                              "script-src 'self'; "
                                              "style-src 'self' 'unsafe-inline'; "
                                              "img-src 'self' https://avatars.githubusercontent.com data:; "
                                              "font-src 'self'; "
                                              "connect-src 'self'; "
                                              "frame-ancestors 'none'")}))))

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
                     ["/api/search" {:get {:handler (rate-limit/wrap-search-rate-limit search/search-api-handler)}}]

                     ;; Auth
                     ["/auth/login" {:get {:handler login-page}
                                    :post {:handler (rate-limit/wrap-login-rate-limit auth/login-handler)}}]
                     ["/auth/register" {:get {:handler register-page}
                                        :post {:handler (rate-limit/wrap-register-rate-limit auth/register-handler)}}]
                     ["/auth/logout" {:get {:handler auth/logout-handler}}]
                     ["/auth/verify-email" {:get {:handler auth/verify-email-handler}}]
                     ["/auth/github" {:get {:handler auth/github-login-handler}}]
                     ["/auth/github/callback" {:get {:handler auth/github-callback-handler}}]
                     ["/auth/forgot-password" {:get {:handler forgot-password-page}
                                               :post {:handler (rate-limit/wrap-reset-rate-limit auth/forgot-password-handler)}}]
                     ["/auth/reset-password" {:get {:handler reset-password-page}
                                              :post {:handler auth/reset-password-handler}}]

                     ;; Examples
                     ["/examples" {:get {:handler examples/list-examples-handler}
                                   :post {:handler (-> examples/create-example-handler
                                                       rate-limit/wrap-example-rate-limit
                                                       wrap-auth)}}]
                     ["/examples/new" {:get {:handler new-example-page}}]
                     ["/examples/:id" {:get {:handler examples/view-example-handler}}]
                     ["/users/:username" {:get {:handler examples/user-profile-handler}}]
                     ["/pdf" {:get {:handler (wrap-auth pdf/pdf-handler)}}]

                     ;; Admin routes
                     ["/admin/examples" {:get {:handler (-> admin/admin-examples-handler
                                                            admin/wrap-require-admin
                                                            wrap-auth)}}]
                     ["/admin/examples/:id/remove" {:post {:handler (-> admin/remove-example-handler
                                                                        admin/wrap-require-admin
                                                                        wrap-auth)}}]
                     ["/admin/examples/:id/restore" {:post {:handler (-> admin/restore-example-handler
                                                                         admin/wrap-require-admin
                                                                         wrap-auth)}}]
                     ["/admin/reindex" {:post {:handler admin/reindex-handler}}]

                     ;; Health
                     ["/health" {:get {:handler (constantly {:status 200 :body "OK"})}}]]
                    router-opts)
                  (ring/create-default-handler
                    {:not-found (constantly {:status 404
                                             :headers {"Content-Type" "text/html; charset=utf-8"}
                                             :body (layout/not-found-page)})}))]
    (-> handler
        (wrap-file "resources/public")
        (wrap-resource "public")
        (biff.mw/wrap-context sys)
        wrap-cookies
        (ring-session/wrap-session {:cookie-attrs {:max-age 86400}})
        wrap-content-type
        wrap-request-logging
        wrap-security-headers
        wrap-exceptions
        (defaults/wrap-defaults defaults/site-defaults))))
