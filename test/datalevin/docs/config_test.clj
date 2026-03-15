(ns datalevin.docs.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.docs.config :as config]))

(deftest session-secret-behavior
  (testing "development falls back to the local default"
    (is (= config/dev-session-secret
           (config/session-secret {:env "dev" :session-secret nil}))))

  (testing "production requires an explicit secret"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"SESSION_SECRET must be set when ENV=prod"
         (config/session-secret {:env "prod" :session-secret nil}))))

  (testing "configured secrets are passed through"
    (is (= "prod-secret"
           (config/session-secret {:env "prod"
                                   :session-secret "prod-secret"})))))
