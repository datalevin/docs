(ns datalevin.docs.views.layout-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datalevin.docs.views.layout :as layout]
            [hiccup2.core :as h]))

(deftest flash-message-uses-data-attributes-for-dismissal
  (let [html (str (h/html {:mode :html :escape? false}
                          (layout/flash-message {:error "boom"})))]
    (testing "flash dismissal does not issue a network request"
      (is (not (str/includes? html "/_flash"))))
    (testing "flash dismissal leaves timing to external javascript"
      (is (not (str/includes? html "<script")))
      (is (str/includes? html "data-flash-auto-dismiss")))))

(deftest base-loads-external-ui-scripts
  (let [html (layout/base "Test Page" [:div "Hello"])]
    (is (re-find #"/js/theme\.js\?v=[0-9a-f]{12}" html))
    (is (re-find #"/js/ui-interactions\.js\?v=[0-9a-f]{12}" html))
    (is (re-find #"/css/output\.css\?v=[0-9a-f]{12}" html))
    (is (not (str/includes? html "onclick=")))))

(deftest anonymous-header-keeps-pdf-public-and-removes-gated-copy
  (let [html (str (h/html {:mode :html :escape? false}
                          (layout/header nil)))]
    (is (str/includes? html "href=\"/pdf\""))
    (is (str/includes? html "title=\"Sign in to post examples\""))
    (is (not (str/includes? html "download the book")))))

(deftest render-example-escapes-code-at-render-time
  (let [html (str (h/html {:mode :html :escape? false}
                          (layout/render-example {:example/id (java.util.UUID/randomUUID)
                                                  :example/code "<script>alert(1)</script>"
                                                  :author {:user/username "alice"}})))]
    (is (str/includes? html "&lt;script&gt;alert(1)&lt;/script&gt;"))
    (is (not (str/includes? html "<code><script>alert(1)</script></code>")))))
