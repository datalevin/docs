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
