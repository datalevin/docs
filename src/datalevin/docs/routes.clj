(ns datalevin.docs.routes
  (:require [biff.datalevin.middleware :as biff.mw]
            [biff.datalevin.session :as session]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datalevin.docs.handlers.pages :as pages]
            [datalevin.docs.handlers.search :as search]
            [datalevin.docs.handlers.auth :as auth]
            [datalevin.docs.handlers.examples :as examples]
            [datalevin.docs.handlers.admin :as admin]
            [datalevin.docs.tasks.pdf :as pdf]
            [datalevin.docs.views.auth :as auth-view]
            [datalevin.docs.views.layout :as layout]
            [reitit.ring :as ring]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.file-info :refer [wrap-file-info]]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [ring.middleware.resource :refer [wrap-resource]]
            [datalevin.docs.middleware.rate-limit :as rate-limit]
            [taoensso.timbre :as log]))

(def router-opts {:conflicts nil})

(defn- session-user
  [req]
  (when-let [session-id (auth/request-session-id req)]
    (when-let [conn (:biff.datalevin/conn req)]
      (session/get-session-user conn session-id))))

(defn- clear-ring-session-user
  [resp browser-session]
  (if (or (contains? (or browser-session {}) :user)
          (get-in resp [:session :user]))
    (update resp :session (fn [s] (dissoc (or s browser-session) :user)))
    resp))

(defn- clear-flash-message
  [resp browser-session]
  (if (and (:flash browser-session)
           (not (get-in resp [:session :flash])))
    (update resp :session (fn [s] (dissoc (or s browser-session) :flash)))
    resp))

(defn- clear-invalid-auth-cookie
  [resp req user]
  (if (and (get-in req [:cookies auth/auth-session-cookie-name :value])
           (nil? user)
           (nil? (get-in resp [:cookies auth/auth-session-cookie-name])))
    (assoc-in resp [:cookies auth/auth-session-cookie-name] (auth/clear-session-cookie req))
    resp))

(defn wrap-session-user
  "Loads :user from the DB-backed auth session cookie, and clears one-shot/stale Ring session state."
  [handler]
  (fn [req]
    (let [browser-session (:session req)
          user (session-user req)
          req (cond-> req
                user (assoc :user user))]
      (-> (handler req)
          (clear-ring-session-user browser-session)
          (clear-flash-message browser-session)
          (clear-invalid-auth-cookie req user)))))

(defn wrap-auth [handler]
  (fn [req]
    (if (:user req)
      (handler req)
      {:status 302
     :headers {"Location" (str "/auth/login?return-to=" (java.net.URLEncoder/encode (:uri req) "UTF-8"))}
     :session (assoc (:session req) :flash {:error "Please log in to continue"})})))

(defn login-page [req]
  (let [return-to (auth/sanitize-return-to
                   (or (get-in req [:params :return-to])
                       (get-in req [:params "return-to"])))]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :session (cond-> (dissoc (:session req) :return-to)
                return-to (assoc :return-to return-to))
     :body (auth-view/login-page req)}))

(defn register-page [req]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (auth-view/register-page req)})

(defn forgot-password-page [req]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (auth-view/forgot-password-page req)})

(defn reset-password-page [req]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (auth-view/reset-password-page req)})

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

(def ^:private security-headers
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
                                  "connect-src 'self' https://api.github.com; "
                                  "frame-ancestors 'none'")})

(defn wrap-security-headers [handler]
  (fn [req]
    (when-let [resp (handler req)]
      (update resp :headers merge security-headers))))

(defn- static-asset-request?
  [{:keys [request-method uri]}]
  (and (contains? #{:get :head} request-method)
       (some #(str/starts-with? uri %)
             ["/css/" "/js/" "/images/"])))

(defn- file-metadata [^java.io.File file]
  {:last-modified (.lastModified file)
   :length (.length file)})

(defn- close-resource-connection! [^java.net.URLConnection conn]
  (when (instance? java.net.HttpURLConnection conn)
    (.disconnect ^java.net.HttpURLConnection conn))
  (when (instance? java.net.JarURLConnection conn)
    (let [jar-conn ^java.net.JarURLConnection conn]
      (some-> (.getJarFile jar-conn) .close))))

(defn- resource-metadata [uri]
  (when-let [resource (io/resource (str "public" uri))]
    (let [conn (.openConnection resource)]
      (try
        (.setUseCaches conn false)
        {:last-modified (.getLastModified conn)
         :length (.getContentLengthLong conn)}
        (finally
          (close-resource-connection! conn))))))

(defn- static-asset-metadata [req resp]
  (cond
    (instance? java.io.File (:body resp))
    (file-metadata (:body resp))

    (static-asset-request? req)
    (resource-metadata (:uri req))

    :else nil))

(defn- static-asset-etag [{:keys [last-modified length]}]
  (when (and (pos? last-modified)
             (<= 0 length))
    (format "W/\"%x-%x\"" last-modified length)))

(defn wrap-static-asset-headers [handler]
  (fn [req]
    (let [resp (handler req)
          env (get-in req [:biff/config :env])]
      (if (and resp
               (contains? #{200 304} (:status resp))
               (static-asset-request? req))
        (let [metadata (when-not (get-in resp [:headers "ETag"])
                         (static-asset-metadata req resp))
              etag (or (get-in resp [:headers "ETag"])
                       (static-asset-etag metadata))]
          (cond-> (assoc-in resp [:headers "Cache-Control"]
                            (if (= env "prod")
                              "public, max-age=3600"
                              "no-cache"))
            etag (assoc-in [:headers "ETag"] etag)))
        resp))))

(defn app [sys]
  (let [env (get-in sys [:biff/config :env])
        handler (ring/ring-handler
                  (ring/router
	                    [;; Public routes
	                     ["/" {:get {:handler pages/home}}]
	                     ["/robots.txt" {:get {:handler pages/robots-txt}}]
	                     ["/sitemap.xml" {:get {:handler pages/sitemap-xml}}]
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
                     ["/auth/logout" {:post {:handler auth/logout-handler}}]
                     ["/auth/verify-email" {:get {:handler auth/verify-email-handler}}]
                     ["/auth/github" {:get {:handler auth/github-login-handler}}]
                     ["/auth/github/callback" {:get {:handler auth/github-callback-handler}}]
                     ["/auth/forgot-password" {:get {:handler forgot-password-page}
                                               :post {:handler (rate-limit/wrap-forgot-password-rate-limit auth/forgot-password-handler)}}]
                     ["/auth/reset-password" {:get {:handler reset-password-page}
                                              :post {:handler (rate-limit/wrap-reset-password-rate-limit auth/reset-password-handler)}}]

                     ;; Examples
                     ["/examples" {:get {:handler examples/list-examples-handler}
                                   :post {:handler (-> examples/create-example-handler
                                                       rate-limit/wrap-example-rate-limit
                                                       wrap-auth)}}]
                     ["/examples/new" {:get {:handler new-example-page}}]
                     ["/examples/form" {:get {:handler new-example-fragment}}]
                     ["/examples/:id" {:get {:handler examples/view-example-handler}}]
                     ["/users/:username" {:get {:handler examples/user-profile-handler}}]
                     ["/pdf" {:get {:handler pdf/pdf-handler}}]

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
                     ["/admin/reindex" {:post {:handler (-> admin/reindex-handler
                                                             admin/wrap-require-admin-or-reindex-secret)}}]

                     ;; Health
                     ["/health" {:get {:handler (constantly {:status 200 :body "OK"})}}]]
                    router-opts)
                  (ring/create-default-handler
                    {:not-found (constantly {:status 404
                                             :headers {"Content-Type" "text/html; charset=utf-8"}
                                             :body (layout/not-found-page)})}))]
    (-> handler
        (wrap-resource "public")
        wrap-file-info
        wrap-not-modified
        wrap-static-asset-headers
        (biff.mw/wrap-context sys)
        wrap-session-user
        wrap-content-type
        wrap-request-logging
        wrap-security-headers
        wrap-exceptions
        (defaults/wrap-defaults
          (-> defaults/site-defaults
              (assoc :static false)
              (assoc-in [:session :cookie-attrs :max-age] 86400)
              (assoc-in [:session :cookie-attrs :secure] (= env "prod")))))))
