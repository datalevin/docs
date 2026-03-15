(ns datalevin.docs.views.layout-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datalevin.docs.views.layout :as layout]
            [hiccup2.core :as h]))

(deftest flash-message-uses-client-side-dismissal
  (let [html (str (h/html {:mode :html :escape? false}
                          (layout/flash-message {:error "boom"})))]
    (testing "flash dismissal does not issue a network request"
      (is (not (str/includes? html "/_flash"))))
    (testing "flash dismissal uses local script instead"
      (is (str/includes? html "setTimeout"))
      (is (str/includes? html "data-flash-auto-dismiss")))))

(deftest render-example-escapes-code-at-render-time
  (let [html (str (h/html {:mode :html :escape? false}
                          (layout/render-example {:example/id (java.util.UUID/randomUUID)
                                                  :example/code "<script>alert(1)</script>"
                                                  :author {:user/username "alice"}})))]
    (is (str/includes? html "&lt;script&gt;alert(1)&lt;/script&gt;"))
    (is (not (str/includes? html "<code><script>alert(1)</script></code>")))))
