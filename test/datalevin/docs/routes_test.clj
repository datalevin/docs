(ns datalevin.docs.routes-test
  (:require [biff.datalevin.session :as session]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datalevin.docs.handlers.auth :as auth]
            [datalevin.docs.handlers.pages :as pages]
            [datalevin.docs.routes :as routes]
            [datalevin.docs.tasks.pdf :as pdf]
            [ring.middleware.anti-forgery :as anti-forgery])
  (:import [java.util UUID]))

(defn- request
  [request-method uri]
  {:request-method request-method
   :uri uri
   :scheme :http
   :server-name "localhost"
   :server-port 3000
   :headers {}
   :session {}})

(defn- request-with-headers
  [request-method uri headers]
  (assoc (request request-method uri) :headers headers))

(deftest logout-route-requires-post-and-csrf
  (let [handler (routes/app {})]
    (testing "GET logout is rejected because the route is POST-only"
      (is (= 405 (:status (handler (request :get "/auth/logout"))))))

    (testing "POST logout is protected by anti-forgery middleware"
      (is (= 403 (:status (handler (request :post "/auth/logout"))))))))

(deftest login-page-sanitizes-return-to
  (binding [anti-forgery/*anti-forgery-token* (delay "test-token")]
    (testing "stores only local return-to values in session"
      (is (= "/docs/01-why-datalevin"
             (get-in (routes/login-page {:params {"return-to" "/docs/01-why-datalevin"}
                                         :session {}})
                     [:session :return-to]))))

    (testing "drops external return-to values"
      (is (nil?
           (get-in (routes/login-page {:params {"return-to" "https://evil.com"}
                                       :session {}})
                   [:session :return-to]))))))

(deftest static-assets-return-cache-validators
  (let [handler (routes/app {:biff/config {:env "prod"}})
        resp (handler (request :get "/css/output.css"))]
    (is (= 200 (:status resp)))
    (is (= "public, max-age=86400"
           (get-in resp [:headers "Cache-Control"])))
    (is (string? (get-in resp [:headers "ETag"])))
    (is (string? (get-in resp [:headers "Last-Modified"])))))

(deftest static-assets-support-conditional-requests
  (let [handler (routes/app {:biff/config {:env "prod"}})
        resp (handler (request :get "/css/output.css"))
        etag (get-in resp [:headers "ETag"])
        last-modified (get-in resp [:headers "Last-Modified"])
        etag-resp (handler (request-with-headers :get "/css/output.css"
                                                 {"if-none-match" etag}))
        last-modified-resp (handler (request-with-headers :get "/css/output.css"
                                                          {"if-modified-since" last-modified}))]
    (is (= 304 (:status etag-resp)))
    (is (= "public, max-age=86400"
           (get-in etag-resp [:headers "Cache-Control"])))
    (is (= etag
           (get-in etag-resp [:headers "ETag"])))
    (is (= 304 (:status last-modified-resp)))
    (is (= "public, max-age=86400"
           (get-in last-modified-resp [:headers "Cache-Control"])))
    (is (= etag
           (get-in last-modified-resp [:headers "ETag"])))
    (is (= last-modified
           (get-in last-modified-resp [:headers "Last-Modified"])))))

(deftest static-assets-stay-non-cached-in-dev
  (let [handler (routes/app {:biff/config {:env "dev"}})]
    (is (= "no-cache"
           (get-in (handler (request :get "/js/theme.js"))
                   [:headers "Cache-Control"])))))

(deftest pdf-route-is-public
  (with-redefs [pdf/pdf-handler (fn [_]
                                  {:status 200
                                   :headers {"Content-Type" "application/pdf"}
                                   :body "pdf"})]
    (let [handler (routes/app {:biff/config {:env "prod"}})
          resp (handler (request :get "/pdf"))]
      (is (= 200 (:status resp)))
      (is (= "application/pdf"
             (get-in resp [:headers "Content-Type"])))
      (is (nil? (get-in resp [:headers "Location"])))
      (is (nil? (get-in resp [:session :flash]))))))

(deftest security-headers-disallow-inline-scripts
  (let [handler (routes/app {:biff/config {:env "prod"}})
        resp (handler (request :get "/robots.txt"))
        csp (get-in resp [:headers "Content-Security-Policy"])]
    (is (string? csp))
    (is (str/includes? csp "script-src 'self';"))
    (is (not (str/includes? csp "script-src 'self' 'unsafe-inline'")))))

(deftest robots-txt-is-served
  (let [handler (routes/app {:base-url "https://docs.example.com"
                             :biff/config {:env "prod"}})
        resp (handler (request :get "/robots.txt"))]
    (is (= 200 (:status resp)))
    (is (= "text/plain; charset=utf-8"
           (get-in resp [:headers "Content-Type"])))
    (is (str/includes? (:body resp) "User-agent: *"))
    (is (str/includes? (:body resp) "Sitemap: https://docs.example.com/sitemap.xml"))))

(deftest sitemap-xml-is-served
  (with-redefs [pages/load-chapter-meta (fn []
                                          [{:slug "01-why-datalevin"
                                            :title "Why Datalevin"
                                            :chapter 1
                                            :part "I"
                                            :lastmod 1700000000000}])]
    (let [handler (routes/app {:base-url "https://docs.example.com"
                               :biff/config {:env "prod"}})
          resp (handler (request :get "/sitemap.xml"))]
      (is (= 200 (:status resp)))
      (is (= "application/xml; charset=utf-8"
             (get-in resp [:headers "Content-Type"])))
      (is (str/includes? (:body resp) "<urlset"))
      (is (str/includes? (:body resp) "<loc>https://docs.example.com/docs/01-why-datalevin</loc>"))
      (is (str/includes? (:body resp) "<loc>https://docs.example.com/examples</loc>")))))

(deftest wrap-session-user-loads-auth-from-db-session-cookie
  (let [session-id (UUID/fromString "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        stale-user {:user/id (UUID/fromString "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
                    :user/role :user}
        fresh-user {:user/id (UUID/fromString "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
                    :user/role :admin}
        handler (routes/wrap-session-user (fn [req] {:status 200
                                                     :body (:user req)}))]
    (with-redefs [session/get-session-user (fn [_ sid]
                                             (when (= sid session-id)
                                               fresh-user))]
      (let [resp (handler {:session {:user stale-user}
                           :cookies {auth/auth-session-cookie-name {:value (str session-id)}}
                           :biff.datalevin/conn ::conn})]
        (is (= fresh-user (:body resp)))
        (is (= {} (:session resp)))
        (is (nil? (get-in resp [:cookies auth/auth-session-cookie-name])))))))

(deftest wrap-session-user-clears-invalid-auth-cookies
  (let [session-id (UUID/fromString "cccccccc-cccc-cccc-cccc-cccccccccccc")
        handler (routes/wrap-session-user (fn [req] {:status 200
                                                     :body (:user req)}))]
    (with-redefs [session/get-session-user (fn [_ _] nil)]
      (let [resp (handler {:session {:user {:user/id (UUID/fromString "dddddddd-dddd-dddd-dddd-dddddddddddd")}}
                           :cookies {auth/auth-session-cookie-name {:value (str session-id)}}
                           :biff.datalevin/conn ::conn
                           :biff/config {:env "prod"}})]
        (is (nil? (:body resp)))
        (is (= {} (:session resp)))
        (is (= "" (get-in resp [:cookies auth/auth-session-cookie-name :value])))
        (is (= 0 (get-in resp [:cookies auth/auth-session-cookie-name :max-age])))
        (is (true? (get-in resp [:cookies auth/auth-session-cookie-name :secure])))))))

(deftest wrap-session-user-skips-db-session-lookup-for-static-assets
  (let [lookups (atom 0)
        handler (routes/wrap-session-user (fn [_] {:status 200
                                                   :body :ok}))]
    (with-redefs [session/get-session-user (fn [& _]
                                             (swap! lookups inc)
                                             nil)]
      (let [resp (handler {:request-method :get
                           :uri "/css/theme-shell.css"
                           :session {:user {:user/id (UUID/fromString "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")}}
                           :cookies {auth/auth-session-cookie-name {:value "ffffffff-ffff-ffff-ffff-ffffffffffff"}}
                           :biff.datalevin/conn ::conn})]
        (is (= 200 (:status resp)))
        (is (= :ok (:body resp)))
        (is (= 0 @lookups))))))
