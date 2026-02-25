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
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.anti-forgery :as anti-forgery]
            [datalevin.docs.middleware.rate-limit :as rate-limit]
            [taoensso.timbre :as log]))

(def router-opts {:conflicts nil})

(defn wrap-session-user
  "Copies :user from Ring session into the request, and clears flash after reading."
  [handler]
  (fn [req]
    (let [user (get-in req [:session :user])
          flash (get-in req [:session :flash])
          req (cond-> req user (assoc :user user))]
      (let [resp (handler req)]
        ;; Clear flash from session after it's been read (one-time display)
        (if (and flash (not (get-in resp [:session :flash])))
          (update resp :session (fn [s] (dissoc (or s (:session req)) :flash)))
          resp)))))

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
              [:div {:class "max-w-md mx-auto py-16 px-4"}
               [:div {:class "bg-white rounded-xl shadow-sm border border-gray-100 p-8"}
                [:h2 {:class "text-2xl font-bold text-gray-900 mb-6 text-center"} "Welcome back"]
                (when error-msg [:p {:class "bg-red-50 text-red-700 px-4 py-3 rounded-lg mb-4 text-sm"} error-msg])
                (when github-configured?
                  [:div {:class "mb-6"}
                   [:a {:href "/auth/github" :hx-boost "false"
                        :class "flex items-center justify-center gap-2 bg-gray-800 text-white px-4 py-2.5 rounded-lg font-medium hover:bg-gray-900 transition"}
                    [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 16 16" :fill "currentColor" :width "20" :height "20"}
                     [:path {:d "M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"}]]
                    "Sign in with GitHub"]
                   [:div {:class "mt-6 pb-2 border-b border-gray-200 text-center"}
                    [:span {:class "text-sm text-gray-400"} "or sign in with email"]]])
                [:form {:method "post" :action "/auth/login"}
                 [:input {:type "hidden" :name "__anti-forgery-token" :value token}]
                 [:div {:class "mb-4"}
                  [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Email"]
                  [:input {:type "email" :name "email" :required true :autocomplete "email"
                           :class "w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"}]]
                 [:div {:class "mb-6"}
                  [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Password"]
                  [:input {:type "password" :name "password" :required true :autocomplete "current-password"
                           :class "w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"}]]
                 [:button {:type "submit" :class "w-full bg-blue-600 text-white py-2.5 rounded-lg font-medium hover:bg-blue-700 transition"}
                  "Log in"]]
                [:div {:class "mt-4 text-center"}
                 [:a {:href "/auth/forgot-password" :class "text-sm text-blue-600 hover:underline"} "Forgot password?"]]
                [:p {:class "mt-6 text-center text-sm text-gray-600"}
                 "Don't have an account? "
                 [:a {:href "/auth/register" :class "text-blue-600 hover:underline font-medium"} "Sign up"]]]])}))

(defn register-page [req]
  (let [token (force anti-forgery/*anti-forgery-token*)
        flash (:flash (:session req))
        error-msg (:error flash)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (layout/base "Sign Up"
              [:div {:class "max-w-md mx-auto py-16 px-4"}
               [:div {:class "bg-white rounded-xl shadow-sm border border-gray-100 p-8"}
                [:h2 {:class "text-2xl font-bold text-gray-900 mb-6 text-center"} "Create your account"]
                (when error-msg [:p {:class "bg-red-50 text-red-700 px-4 py-3 rounded-lg mb-4 text-sm"} error-msg])
                [:form {:method "post" :action "/auth/register"}
                 [:input {:type "hidden" :name "__anti-forgery-token" :value token}]
                 [:div {:class "mb-4"}
                  [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Email"]
                  [:input {:type "email" :name "email" :required true :autocomplete "email"
                           :class "w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"}]]
                 [:div {:class "mb-4"}
                  [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Username"]
                  [:input {:type "text" :name "username" :required true :autocomplete "username"
                           :class "w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"}]]
                 [:div {:class "mb-4"}
                  [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Password"]
                  [:input {:type "password" :name "password" :minLength 8 :required true :autocomplete "new-password"
                           :class "w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"}]]
                 [:div {:class "mb-6"}
                  [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Confirm Password"]
                  [:input {:type "password" :name "confirm-password" :required true :autocomplete "new-password"
                           :class "w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"}]]
                 [:button {:type "submit" :class "w-full bg-blue-600 text-white py-2.5 rounded-lg font-medium hover:bg-blue-700 transition"}
                  "Create account"]]
                [:p {:class "mt-6 text-center text-sm text-gray-600"}
                 "Already have an account? "
                 [:a {:href "/auth/login" :class "text-blue-600 hover:underline font-medium"} "Log in"]]]])}))

(defn forgot-password-page [req]
  (let [token (force anti-forgery/*anti-forgery-token*)
        flash (:flash (:session req))
        error-msg (:error flash)
        success-msg (:success flash)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (layout/base "Forgot Password"
              [:div {:class "max-w-md mx-auto py-16 px-4"}
               [:div {:class "bg-white rounded-xl shadow-sm border border-gray-100 p-8"}
                [:h2 {:class "text-2xl font-bold text-gray-900 mb-6 text-center"} "Reset your password"]
                (when error-msg [:p {:class "bg-red-50 text-red-700 px-4 py-3 rounded-lg mb-4 text-sm"} error-msg])
                (when success-msg [:p {:class "bg-green-50 text-green-700 px-4 py-3 rounded-lg mb-4 text-sm"} success-msg])
                [:form {:method "post" :action "/auth/forgot-password"}
                 [:input {:type "hidden" :name "__anti-forgery-token" :value token}]
                 [:div {:class "mb-6"}
                  [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Email"]
                  [:input {:type "email" :name "email" :required true
                           :class "w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"}]]
                 [:button {:type "submit" :class "w-full bg-blue-600 text-white py-2.5 rounded-lg font-medium hover:bg-blue-700 transition"}
                  "Send reset link"]]
                [:p {:class "mt-6 text-center text-sm text-gray-600"}
                 [:a {:href "/auth/login" :class "text-blue-600 hover:underline"} "Back to login"]]]])}))

(defn reset-password-page [req]
  (let [token-param (get-in req [:params "token"] "")
        csrf-token (force anti-forgery/*anti-forgery-token*)
        flash (:flash (:session req))
        error-msg (:error flash)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (layout/base "Reset Password"
              [:div {:class "max-w-md mx-auto py-16 px-4"}
               [:div {:class "bg-white rounded-xl shadow-sm border border-gray-100 p-8"}
                [:h2 {:class "text-2xl font-bold text-gray-900 mb-6 text-center"} "Set new password"]
                (when error-msg [:p {:class "bg-red-50 text-red-700 px-4 py-3 rounded-lg mb-4 text-sm"} error-msg])
                [:form {:method "post" :action "/auth/reset-password"}
                 [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
                 [:input {:type "hidden" :name "token" :value token-param}]
                 [:div {:class "mb-4"}
                  [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "New Password"]
                  [:input {:type "password" :name "password" :minLength 8 :required true
                           :class "w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"}]]
                 [:div {:class "mb-6"}
                  [:label {:class "block text-sm font-medium text-gray-700 mb-1"} "Confirm Password"]
                  [:input {:type "password" :name "confirm-password" :required true
                           :class "w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"}]]
                 [:button {:type "submit" :class "w-full bg-blue-600 text-white py-2.5 rounded-lg font-medium hover:bg-blue-700 transition"}
                  "Reset password"]]]])}))

(defn new-example-page [req]
  (if (:user req)
    (examples/new-example-form req)
    {:status 302
     :headers {"Location" "/auth/login"}
     :session (assoc (:session req) :flash {:error "Please log in"})}))

(defn new-example-fragment [req]
  (if (:user req)
    (examples/new-example-form-fragment req)
    {:status 200
     :headers {"Content-Type" "text/html"
               "HX-Redirect" "/auth/login"}
     :body ""}))

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
                                              "script-src 'self' 'unsafe-inline'; "
                                              "style-src 'self' 'unsafe-inline'; "
                                              "img-src 'self' https://avatars.githubusercontent.com data:; "
                                              "font-src 'self'; "
                                              "connect-src 'self' https://api.github.com; "
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
                     ["/examples/form" {:get {:handler new-example-fragment}}]
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
        wrap-session-user
        wrap-content-type
        wrap-request-logging
        wrap-security-headers
        wrap-exceptions
        (defaults/wrap-defaults
          (-> defaults/site-defaults
              (assoc-in [:session :cookie-attrs :max-age] 86400))))))
