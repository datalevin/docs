(ns datalevin.docs.handlers.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.docs.handlers.auth :as auth]))

(deftest sanitize-return-to-behavior
  (testing "accepts local paths"
    (is (= "/docs/01-why-datalevin"
           (auth/sanitize-return-to "/docs/01-why-datalevin"))))

  (testing "rejects external redirects"
    (is (nil? (auth/sanitize-return-to "https://evil.com")))
    (is (nil? (auth/sanitize-return-to "//evil.com"))))

  (testing "rejects blank and relative paths"
    (is (nil? (auth/sanitize-return-to "")))
    (is (nil? (auth/sanitize-return-to "docs/01-why-datalevin")))))
