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

(deftest mail-config-behavior
  (testing "development can omit mail delivery"
    (is (nil? (config/mail-config {:env "dev"}))))

  (testing "production requires explicit mail settings"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Email delivery requires MAIL_FROM and SMTP_HOST"
         (config/mail-config {:env "prod"}))))

  (testing "configured mail settings are normalized"
    (is (= {:from "docs@example.com"
            :server {:host "smtp.example.com"
                     :port 587
                     :user "mailer"
                     :pass "secret"
                     :tls true}}
           (config/mail-config {:env "prod"
                                :mail-from "docs@example.com"
                                :smtp-host "smtp.example.com"
                                :smtp-port 587
                                :smtp-user "mailer"
                                :smtp-pass "secret"
                                :smtp-tls true})))))
