(ns datalevin.docs.handlers.auth-test
  (:require [biff.datalevin.auth :as biff-auth]
            [biff.datalevin.session :as session]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
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
                                      :admin-emails #{"admin@example.com"}
                                      :biff.datalevin/conn :conn})]
        (is (= 302 (:status resp)))
        (is (= :admin (get-in resp [:session :user :user/role])))
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
                                               :admin-emails #{"admin@example.com"}
                                               :github-client-id "client"
                                               :github-client-secret "secret"
                                               :base-url "https://docs.example.com"
                                               :biff.datalevin/conn :conn})]
        (is (= 302 (:status resp)))
        (is (= :admin (get-in resp [:session :user :user/role])))
        (is (some #(= [{:db/id [:user/id user-id]
                        :user/role :admin}]
                      %)
                  @txs))
        (is (some #(= [{:session/id session-id
                        :session/user [:user/id user-id]}]
                      %)
                  @txs))))))
