(ns datalevin.docs.handlers.auth
  (:require [biff.datalevin.auth :as auth]
            [biff.datalevin.session :as session]
            [biff.datalevin.db :as db]
            [datalevin.core :as d]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.util Date UUID]))

;; =============================================================================
;; Registration (with email verification)
;; =============================================================================

(defn register-handler [{:keys [params session biff.datalevin/conn] :as req}]
  (let [email (str/trim (get params "email" ""))
        username (str/trim (get params "username" ""))
        password (get params "password" "")
        confirm-password (get params "confirm-password" "")
        base-url (:base-url req)]
    (if (or (empty? email) (empty? username) (empty? password))
      {:status 302 :session (assoc session :flash {:error "All fields required"}) :headers {"Location" "/auth/register"}}
      (if (not (= password confirm-password))
        {:status 302 :session (assoc session :flash {:error "Passwords mismatch"}) :headers {"Location" "/auth/register"}}
        (if (< (count password) 8)
          {:status 302 :session (assoc session :flash {:error "Password too short"}) :headers {"Location" "/auth/register"}}
          (let [user-id (UUID/randomUUID)
                user-tx {:user/id user-id :user/email email :user/username username
                         :user/password-hash (auth/hash-password password)
                         :user/role :user :user/email-verified? false :user/created-at (Date.)}
                session-tx (session/create-session [:user/id user-id])
                ;; Create email verification token
                vtoken (auth/create-verification-token user-id)]
            (d/transact! conn [user-tx (:tx session-tx) (:tx vtoken)])
            (log/info "=== Email verification link ===" (str base-url "/auth/verify-email?token=" (:token vtoken)))
            {:status 302
             :session (assoc session :user (dissoc user-tx :user/password-hash) :flash {:success "Welcome! Check your email to verify your account."})
             :headers {"Location" "/"}
             :cookies {"session" {:value (str (:session-id session-tx))}}}))))))

;; =============================================================================
;; Login / Logout
;; =============================================================================

(defn login-handler [{:keys [params session biff.datalevin/conn] :as req}]
  (let [email (str/trim (get params "email" ""))
        password (get params "password" "")
        db (d/db conn)]
    (if-let [user (auth/authenticate-user db email password)]
      (let [session-tx (session/create-session [:user/id (:user/id user)])]
        (d/transact! conn [(:tx session-tx)])
        {:status 302
         :session (assoc session :user (dissoc user :user/password-hash))
         :headers {"Location" "/"}
         :cookies {"session" {:value (str (:session-id session-tx))}}})
      {:status 302
       :session (assoc session :flash {:error "Invalid credentials"})
       :headers {"Location" "/auth/login"}})))

(defn logout-handler [{:keys [session biff.datalevin/conn] :as req}]
  (let [session-id (get-in req [:cookies "session" :value])]
    (when session-id
      (d/transact! conn [[:db.fn/retractEntity [:session/id (UUID/fromString session-id)]]]))
    {:status 302 :session (dissoc session :user) :headers {"Location" "/"}}))

(defn require-auth [handler]
  (fn [req]
    (if (:user req)
      (handler req)
      {:status 302 :headers {"Location" "/auth/login"} :session (assoc (:session req) :flash {:error "Login required"})})))

;; =============================================================================
;; Email Verification
;; =============================================================================

(defn verify-email-handler [{:keys [params biff.datalevin/conn session] :as req}]
  (let [token (get params "token" "")
        db (d/db conn)]
    (if-let [user-id (auth/verify-token db token)]
      (let [delete-tx (auth/delete-verification-token-tx db token)]
        (d/transact! conn (cond-> [{:db/id [:user/id user-id] :user/email-verified? true}]
                            delete-tx (conj delete-tx)))
        {:status 302
         :session (assoc session :flash {:success "Email verified!"})
         :headers {"Location" "/"}})
      {:status 302
       :session (assoc session :flash {:error "Invalid or expired verification link"})
       :headers {"Location" "/"}})))

;; =============================================================================
;; GitHub OAuth
;; =============================================================================

(defn github-login-handler [{:keys [session] :as req}]
  (let [state (str (UUID/randomUUID))
        client-id (:github-client-id req)
        base-url (:base-url req)
        redirect-uri (str base-url "/auth/github/callback")]
    (if (str/blank? client-id)
      {:status 302
       :session (assoc session :flash {:error "GitHub OAuth not configured"})
       :headers {"Location" "/auth/login"}}
      {:status 302
       :session (assoc session :github-oauth-state state)
       :headers {"Location" (auth/github-authorize-url {:client-id client-id
                                                         :redirect-uri redirect-uri
                                                         :state state})}})))

(defn github-callback-handler [{:keys [params session biff.datalevin/conn] :as req}]
  (let [code (get params "code" "")
        state (get params "state" "")
        expected-state (:github-oauth-state session)
        client-id (:github-client-id req)
        client-secret (:github-client-secret req)
        base-url (:base-url req)
        redirect-uri (str base-url "/auth/github/callback")]
    (if (or (empty? code) (empty? state) (not= state expected-state))
      {:status 302
       :session (assoc (dissoc session :github-oauth-state) :flash {:error "OAuth failed — invalid state"})
       :headers {"Location" "/auth/login"}}
      (try
        (let [token-resp (auth/github-exchange-code {:client-id client-id
                                                     :client-secret client-secret
                                                     :code code
                                                     :redirect-uri redirect-uri})
              access-token (:access_token token-resp)
              gh-user (auth/github-get-user access-token)
              db (d/db conn)
              existing (auth/find-user-by-github-id db (:id gh-user))
              user (if existing
                     existing
                     (let [tx (auth/github-find-or-create-user-tx gh-user)
                           tx (assoc tx :user/role :user :user/email-verified? true)]
                       (d/transact! conn [tx])
                       tx))
              session-tx (session/create-session [:user/id (:user/id user)])]
          (d/transact! conn [(:tx session-tx)])
          {:status 302
           :session (assoc (dissoc session :github-oauth-state) :user (dissoc user :user/password-hash))
           :headers {"Location" "/"}
           :cookies {"session" {:value (str (:session-id session-tx))}}})
        (catch Exception e
          (log/error e "GitHub OAuth error")
          {:status 302
           :session (assoc (dissoc session :github-oauth-state) :flash {:error "GitHub login failed"})
           :headers {"Location" "/auth/login"}})))))

;; =============================================================================
;; Password Reset
;; =============================================================================

(defn forgot-password-page [req]
  nil) ;; Rendered inline in routes.clj

(defn forgot-password-handler [{:keys [params session biff.datalevin/conn] :as req}]
  (let [email (str/trim (get params "email" ""))
        db (d/db conn)
        base-url (:base-url req)
        ;; Always show same message to prevent email enumeration
        success-msg "If an account with that email exists, a reset link has been sent."]
    (when-let [user (auth/find-user-by-email db email)]
      (let [token (str (UUID/randomUUID))
            expires-at (Date. (+ (System/currentTimeMillis) (* 1 60 60 1000))) ;; 1 hour
            reset-tx {:password-reset/token token
                      :password-reset/user [:user/id (:user/id user)]
                      :password-reset/expires-at expires-at}]
        (d/transact! conn [reset-tx])
        (log/info "=== Password reset link ===" (str base-url "/auth/reset-password?token=" token))))
    {:status 302
     :session (assoc session :flash {:success success-msg})
     :headers {"Location" "/auth/forgot-password"}}))

(defn- lookup-reset-token [db token]
  (when (seq token)
    (let [entity (db/lookup db :password-reset/token token
                            '[:password-reset/token :password-reset/expires-at
                              {:password-reset/user [:user/id]}])]
      (when entity
        (let [expires-at (:password-reset/expires-at entity)]
          (when (.before (Date.) expires-at)
            (get-in entity [:password-reset/user :user/id])))))))

(defn reset-password-page [req]
  nil) ;; Rendered inline in routes.clj

(defn reset-password-handler [{:keys [params session biff.datalevin/conn] :as req}]
  (let [token (get params "token" "")
        password (get params "password" "")
        confirm-password (get params "confirm-password" "")
        db (d/db conn)]
    (if-let [user-id (lookup-reset-token db token)]
      (cond
        (< (count password) 8)
        {:status 302
         :session (assoc session :flash {:error "Password must be at least 8 characters"})
         :headers {"Location" (str "/auth/reset-password?token=" token)}}

        (not= password confirm-password)
        {:status 302
         :session (assoc session :flash {:error "Passwords do not match"})
         :headers {"Location" (str "/auth/reset-password?token=" token)}}

        :else
        (let [delete-eid (db/lookup-id db :password-reset/token token)]
          (d/transact! conn (cond-> [{:db/id [:user/id user-id]
                                      :user/password-hash (auth/hash-password password)}]
                              delete-eid (conj [:db/retractEntity delete-eid])))
          {:status 302
           :session (assoc session :flash {:success "Password updated! Please log in."})
           :headers {"Location" "/auth/login"}}))
      {:status 302
       :session (assoc session :flash {:error "Invalid or expired reset link"})
       :headers {"Location" "/auth/forgot-password"}})))
