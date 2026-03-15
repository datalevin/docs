(ns datalevin.docs.handlers.auth-test
  (:require [biff.datalevin.auth :as biff-auth]
    [biff.datalevin.session :as session]
    [clojure.test :refer [deftest is testing]]
    [datalevin.core :as d]
    [datalevin.docs.mail :as mail]
    [datalevin.docs.handlers.auth :as auth])
  (:import [java.util UUID]))

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

(deftest login-handler-persists-admin-role-upgrade
  (let [txs (atom [])
        user-id (UUID/fromString "11111111-1111-1111-1111-111111111111")
        session-id (UUID/fromString "22222222-2222-2222-2222-222222222222")]
    (with-redefs [d/db (fn [_] :db)
                  d/transact! (fn [_ tx] (swap! txs conj tx))
                  biff-auth/authenticate-user (fn [_ email password]
                                                (when (and (= email "admin@example.com")
                                                           (= password "secret"))
                                                  {:user/id user-id
                                                   :user/email email
                                                   :user/role :user
                                                   :user/password-hash "hash"}))
                  session/create-session (fn [lookup]
                                           {:tx {:session/id session-id
                                                 :session/user lookup}
                                            :session-id session-id})]
      (let [resp (auth/login-handler {:params {"email" "admin@example.com"
                                               "password" "secret"}
                                      :session {:return-to "/docs"}
                                      :biff/config {:env "prod"}
                                      :admin-emails #{"admin@example.com"}
                                      :biff.datalevin/conn :conn})]
        (is (= 302 (:status resp)))
        (is (nil? (get-in resp [:session :user])))
        (is (= (str session-id)
               (get-in resp [:cookies auth/auth-session-cookie-name :value])))
        (is (true? (get-in resp [:cookies auth/auth-session-cookie-name :secure])))
        (is (some #(= [{:db/id [:user/id user-id]
                        :user/role :admin}]
                      %)
                  @txs))
        (is (some #(= [{:session/id session-id
                        :session/user [:user/id user-id]}]
                      %)
                  @txs))))))

(deftest github-callback-persists-admin-role-upgrade
  (let [txs (atom [])
        user-id (UUID/fromString "33333333-3333-3333-3333-333333333333")
        session-id (UUID/fromString "44444444-4444-4444-4444-444444444444")]
    (with-redefs [d/db (fn [_] :db)
                  d/transact! (fn [_ tx] (swap! txs conj tx))
                  biff-auth/github-exchange-code (fn [_] {:access_token "token"})
                  biff-auth/github-get-user (fn [_] {:id 42 :login "admin" :email "admin@example.com"})
                  biff-auth/find-user-by-github-id (fn [_ _]
                                                    {:user/id user-id
                                                     :user/email "admin@example.com"
                                                     :user/role :user})
                  session/create-session (fn [lookup]
                                           {:tx {:session/id session-id
                                                 :session/user lookup}
                                            :session-id session-id})]
      (let [resp (auth/github-callback-handler {:params {"code" "code"
                                                         "state" "expected"}
                                               :session {:github-oauth-state "expected"
                                                         :return-to "/docs"}
                                               :biff/config {:env "prod"}
                                               :admin-emails #{"admin@example.com"}
                                               :github-client-id "client"
                                               :github-client-secret "secret"
                                               :base-url "https://docs.example.com"
                                               :biff.datalevin/conn :conn})]
        (is (= 302 (:status resp)))
        (is (nil? (get-in resp [:session :user])))
        (is (= (str session-id)
               (get-in resp [:cookies auth/auth-session-cookie-name :value])))
        (is (true? (get-in resp [:cookies auth/auth-session-cookie-name :secure])))
        (is (some #(= [{:db/id [:user/id user-id]
                        :user/role :admin}]
                      %)
                  @txs))
        (is (some #(= [{:session/id session-id
                        :session/user [:user/id user-id]}]
                      %)
                  @txs))))))

(deftest register-handler-sends-verification-email
  (let [txs (atom [])
        deliveries (atom [])
        user-id (UUID/fromString "55555555-5555-5555-5555-555555555555")
        session-id (UUID/fromString "66666666-6666-6666-6666-666666666666")]
    (with-redefs [biff-auth/hash-password (constantly "hashed")
                  biff-auth/create-verification-token (fn [uid]
                                                        {:token "verify-token"
                                                         :tx {:verification-token/token "verify-token"
                                                              :verification-token/user [:user/id uid]}})
                  session/create-session (fn [lookup]
                                           {:tx {:session/id session-id
                                                 :session/user lookup}
                                            :session-id session-id})
                  d/transact! (fn [_ tx]
                                (swap! txs conj tx))
                  mail/send-verification-email! (fn [_ payload]
                                                  (swap! deliveries conj payload)
                                                  {:code 0})]
      (let [resp (auth/register-handler {:params {"email" "new@example.com"
                                                  "username" "newuser"
                                                  "password" "password123"
                                                  "confirm-password" "password123"}
                                         :session {}
                                         :base-url "https://docs.example.com"
                                         :biff/config {:env "prod"}
                                         :biff.datalevin/conn :conn})]
        (is (= 302 (:status resp)))
        (is (= [{:to "new@example.com"
                 :username "newuser"
                 :verify-url "https://docs.example.com/auth/verify-email?token=verify-token"}]
               @deliveries))
        (is (= "Welcome! Check your email to verify your account."
               (get-in resp [:session :flash :success])))
        (is (= (str session-id)
               (get-in resp [:cookies auth/auth-session-cookie-name :value])))
        (is (= 1 (count @txs)))
        (is (= "hashed"
               (get-in (first @txs) [0 :user/password-hash])))))))

(deftest register-handler-rolls-back-when-email-delivery-fails
  (let [txs (atom [])
        session-id (UUID/fromString "77777777-7777-7777-7777-777777777777")]
    (with-redefs [biff-auth/hash-password (constantly "hashed")
                  biff-auth/create-verification-token (fn [uid]
                                                        {:token "verify-token"
                                                         :tx {:verification-token/token "verify-token"
                                                              :verification-token/user [:user/id uid]}})
                  session/create-session (fn [lookup]
                                           {:tx {:session/id session-id
                                                 :session/user lookup}
                                            :session-id session-id})
                  d/transact! (fn [_ tx]
                                (swap! txs conj tx))
                  mail/send-verification-email! (fn [& _]
                                                  (throw (ex-info "smtp down" {})))]
      (let [resp (auth/register-handler {:params {"email" "new@example.com"
                                                  "username" "newuser"
                                                  "password" "password123"
                                                  "confirm-password" "password123"}
                                         :session {}
                                         :base-url "https://docs.example.com"
                                         :biff/config {:env "prod"}
                                         :biff.datalevin/conn :conn})
            user-id (get-in (first @txs) [0 :user/id])]
        (is (= 302 (:status resp)))
        (is (= "/auth/register" (get-in resp [:headers "Location"])))
        (is (= "Could not send verification email. Please try again."
               (get-in resp [:session :flash :error])))
        (is (= [[:db.fn/retractEntity [:session/id session-id]]
                [:db.fn/retractEntity [:verification-token/token "verify-token"]]
                [:db.fn/retractEntity [:user/id user-id]]]
               (second @txs)))))))

(deftest forgot-password-handler-sends-reset-email
  (let [txs (atom [])
        deliveries (atom [])
        user-id (UUID/fromString "88888888-8888-8888-8888-888888888888")]
    (with-redefs [d/db (fn [_] :db)
                  biff-auth/find-user-by-email (fn [_ email]
                                                 (when (= email "user@example.com")
                                                   {:user/id user-id}))
                  d/transact! (fn [_ tx]
                                (swap! txs conj tx))
                  mail/send-password-reset-email! (fn [_ payload]
                                                    (swap! deliveries conj payload)
                                                    {:code 0})]
      (let [resp (auth/forgot-password-handler {:params {"email" "user@example.com"}
                                                :session {}
                                                :base-url "https://docs.example.com"
                                                :biff/config {:env "prod"}
                                                :biff.datalevin/conn :conn})
            token (get-in (first @txs) [0 :password-reset/token])]
        (is (= 302 (:status resp)))
        (is (= [{:to "user@example.com"
                 :reset-url (str "https://docs.example.com/auth/reset-password?token=" token)}]
               @deliveries))
        (is (= "If an account with that email exists, a reset link has been sent."
               (get-in resp [:session :flash :success])))))))
