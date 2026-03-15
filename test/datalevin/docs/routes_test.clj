(ns datalevin.docs.routes-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.docs.routes :as routes]
            [ring.middleware.anti-forgery :as anti-forgery]))

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
    (is (= "public, max-age=3600"
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
    (is (= "public, max-age=3600"
           (get-in etag-resp [:headers "Cache-Control"])))
    (is (= etag
           (get-in etag-resp [:headers "ETag"])))
    (is (= 304 (:status last-modified-resp)))
    (is (= "public, max-age=3600"
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
