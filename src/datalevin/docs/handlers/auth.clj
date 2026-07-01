(ns datalevin.docs.handlers.auth
  (:require [biff.datalevin.auth :as auth]
            [biff.datalevin.session :as session]
            [biff.datalevin.db :as db]
            [datalevin.core :as d]
            [clojure.string :as str]
            [datalevin.docs.mail :as mail]
            [taoensso.timbre :as log])
  (:import [java.util Date UUID]))

(defn- param
  "Get a parameter by name, checking both keyword and string keys."
  [params k]
  (or (get params (keyword k)) (get params (str k)) ""))

(defn- blank->nil
  [s]
  (when-not (str/blank? s)
    s))

(defn sanitize-return-to
  "Allow only local application paths for post-auth redirects."
  [return-to]
  (when (and (string? return-to)
             (str/starts-with? return-to "/")
             (not (str/starts-with? return-to "//")))
    return-to))

(defn- post-auth-location
  [session]
  (or (sanitize-return-to (:return-to session))
      "/"))

(def auth-session-cookie-name "session")

(def ^:private auth-session-max-age-seconds
  (* 7 24 60 60))

(defn session-cookie
  [{:keys [biff/config] :as _req} session-id]
  {:value (str session-id)
   :path "/"
   :http-only true
   :same-site :lax
   :max-age auth-session-max-age-seconds
   :secure (= "prod" (:env config))})

(defn clear-session-cookie
  [{:keys [biff/config] :as _req}]
  {:value ""
   :path "/"
   :http-only true
   :same-site :lax
   :max-age 0
   :secure (= "prod" (:env config))})

(defn request-session-id
  [req]
  (try
    (some-> (get-in req [:cookies auth-session-cookie-name :value])
            UUID/fromString)
    (catch Exception _
      nil)))

(defn- verification-url
  [base-url token]
  (str base-url "/auth/verify-email?token=" token))

(defn- reset-password-url
  [base-url token]
  (str base-url "/auth/reset-password?token=" token))

(defn- retract-lookup-refs!
  [conn lookup-refs]
  (when (seq lookup-refs)
    (try
      (d/transact! conn (mapv (fn [lookup-ref]
                                [:db.fn/retractEntity lookup-ref])
                              lookup-refs))
      (catch Exception e
        (log/error e "Compensation transaction failed" {:lookup-refs lookup-refs})))))

(defn- effective-user-role
  [admin-emails user]
  (if (and (:user/email user)
           (contains? admin-emails (:user/email user)))
    :admin
    (:user/role user)))

(defn- ensure-user-role!
  [conn admin-emails user]
  (let [current-role (:user/role user)
        role (effective-user-role admin-emails user)]
    (when (and (:user/id user)
               (not= role current-role))
      (d/transact! conn [{:db/id [:user/id (:user/id user)]
                          :user/role role}]))
    (assoc user :user/role role)))

(defn- compact-tx
  [tx]
  (into {} (remove (fn [[_ v]] (nil? v))) tx))

(defn- normalize-username
  [value]
  (let [username (-> (or value "user")
                     str/lower-case
                     (str/replace #"@.*$" "")
                     (str/replace #"[^a-z0-9_-]+" "-")
                     (str/replace #"^-+|-+$" ""))]
    (if (str/blank? username)
      "user"
      (subs username 0 (min 32 (count username))))))

(defn- with-username-suffix
  [base n]
  (let [suffix (str "-" n)
        max-base-len (max 1 (- 32 (count suffix)))]
    (str (subs base 0 (min max-base-len (count base))) suffix)))

(defn- username-taken?
  [db username]
  (boolean (db/lookup-id db :user/username username)))

(defn- unique-username
  [db value]
  (let [base (normalize-username value)]
    (loop [n 0]
      (let [candidate (if (zero? n)
                        base
                        (with-username-suffix base (inc n)))]
        (cond
          (not (username-taken? db candidate))
          candidate

          (< n 1000)
          (recur (inc n))

          :else
          (str (subs base 0 (min 23 (count base)))
               "-"
               (subs (str (UUID/randomUUID)) 0 8)))))))

(defn- email-owned-by-user?
  [db email user-id]
  (if-let [user (and email (auth/find-user-by-email db email))]
    (= user-id (:user/id user))
    true))

(defn- oauth-configured?
  [client-id client-secret]
  (and (seq client-id) (seq client-secret)))

(defn- oauth-login-response
  [{:keys [session] :as req} {:keys [client-id client-secret callback-path state-key authorize-url-fn provider-name]}]
  (let [state (str (UUID/randomUUID))
        base-url (:base-url req)
        redirect-uri (str base-url callback-path)]
    (if-not (oauth-configured? client-id client-secret)
      {:status 302
       :session (assoc session :flash {:error (str provider-name " OAuth not configured")})
       :headers {"Location" "/auth/login"}}
      {:status 302
       :session (assoc session state-key state)
       :headers {"Location" (authorize-url-fn {:client-id client-id
                                                :redirect-uri redirect-uri
                                                :state state})}})))

(defn- oauth-invalid-state-response
  [session state-key]
  {:status 302
   :session (assoc (dissoc session state-key) :flash {:error "OAuth failed: invalid state"})
   :headers {"Location" "/auth/login"}})

(defn- oauth-failure-response
  [session state-key provider-name]
  {:status 302
   :session (assoc (dissoc session state-key) :flash {:error (str provider-name " login failed")})
   :headers {"Location" "/auth/login"}})

(defn- login-session-response
  [req session user state-key]
  (let [session-tx (session/create-session [:user/id (:user/id user)])]
    (d/transact! (:biff.datalevin/conn req) [(:tx session-tx)])
    {:status 302
     :session (dissoc session state-key :return-to :user)
     :headers {"Location" (post-auth-location session)}
     :cookies {auth-session-cookie-name (session-cookie req (:session-id session-tx))}}))

(defn- provider-attr-updates
  [user provider-attrs]
  (reduce-kv
   (fn [updates k v]
     (let [current (get user k)]
       (cond
         (nil? v) updates
         (nil? current) (assoc updates k v)
         (= current v) updates
         :else (throw (ex-info "OAuth account is already linked to another provider identity"
                               {:attr k})))))
   {}
   provider-attrs))

(defn- profile-updates
  [db user {:keys [email username-base avatar-url email-verified? update-attrs]}]
  (let [attach-email? (and (str/blank? (:user/email user))
                           email
                           (email-owned-by-user? db email (:user/id user)))
        has-email? (or (:user/email user) attach-email?)]
    (merge
     (compact-tx update-attrs)
     (cond-> {}
       attach-email?
       (assoc :user/email email)

       (str/blank? (:user/username user))
       (assoc :user/username (unique-username db username-base))

       (and (str/blank? (:user/avatar-url user))
            avatar-url)
       (assoc :user/avatar-url avatar-url)

       (and email-verified? has-email?)
       (assoc :user/email-verified? true)))))

(defn- ensure-oauth-user!
  [conn db user provider-attrs profile]
  (let [updates (merge (provider-attr-updates user provider-attrs)
                       (profile-updates db user profile))]
    (when (seq updates)
      (d/transact! conn [(assoc updates :db/id [:user/id (:user/id user)])]))
    (merge user updates)))

(defn- create-oauth-user!
  [conn db base-tx {:keys [email username-base avatar-url email-verified?]}]
  (let [tx (-> base-tx
               (assoc :user/email email
                      :user/username (unique-username db username-base)
                      :user/avatar-url avatar-url
                      :user/role :user
                      :user/email-verified? (boolean email-verified?))
               compact-tx)]
    (d/transact! conn [tx])
    tx))

(defn- find-or-create-oauth-user!
  [conn db {:keys [provider-id find-by-provider base-tx provider-attrs profile]}]
  (if-let [user (find-by-provider db provider-id)]
    (ensure-oauth-user! conn db user provider-attrs profile)
    (if-let [user (when-let [email (:email profile)]
                    (auth/find-user-by-email db email))]
      (ensure-oauth-user! conn db user provider-attrs profile)
      (create-oauth-user! conn db base-tx profile))))

;; =============================================================================
;; Registration (with email verification)
;; =============================================================================

(defn register-handler [{:keys [params session biff.datalevin/conn] :as req}]
  (let [email (str/trim (param params "email"))
        username (str/trim (param params "username"))
        password (param params "password")
        confirm-password (param params "confirm-password")
        base-url (:base-url req)]
    (if (or (empty? email) (empty? username) (empty? password))
      {:status 302 :session (assoc session :flash {:error "All fields required"}) :headers {"Location" "/auth/register"}}
      (if (not= password confirm-password)
        {:status 302 :session (assoc session :flash {:error "Passwords mismatch"}) :headers {"Location" "/auth/register"}}
        (if (< (count password) 8)
          {:status 302 :session (assoc session :flash {:error "Password too short"}) :headers {"Location" "/auth/register"}}
          (let [user-id (UUID/randomUUID)
                user-tx {:user/id user-id :user/email email :user/username username
                         :user/password-hash (auth/hash-password password)
                         :user/role :user :user/email-verified? false :user/created-at (Date.)}
                session-tx (session/create-session [:user/id user-id])
                ;; Create email verification token
                vtoken (auth/create-verification-token user-id)
                verify-url (verification-url base-url (:token vtoken))]
            (d/transact! conn [user-tx (:tx session-tx) (:tx vtoken)])
            (try
              (mail/send-verification-email! req {:to email
                                                  :username username
                                                  :verify-url verify-url})
              {:status 302
               :session (-> session
                            (dissoc :user)
                            (assoc :flash {:success "Welcome! Check your email to verify your account."}))
               :headers {"Location" "/"}
               :cookies {auth-session-cookie-name (session-cookie req (:session-id session-tx))}}
              (catch Exception e
                (log/error e "Failed to send verification email" {:email email})
                (retract-lookup-refs! conn [[:session/id (:session-id session-tx)]
                                            [:verification-token/token (:token vtoken)]
                                            [:user/id user-id]])
                {:status 302
                 :session (assoc session :flash {:error "Could not send verification email. Please try again."})
                 :headers {"Location" "/auth/register"}}))))))))

;; =============================================================================
;; Login / Logout
;; =============================================================================

(defn login-handler [{:keys [params session biff.datalevin/conn admin-emails] :as req}]
  (let [email (str/trim (param params "email"))
        password (param params "password")
        db (d/db conn)]
    (if-let [user (auth/authenticate-user db email password)]
      (let [user (ensure-user-role! conn admin-emails user)
            session-tx (session/create-session [:user/id (:user/id user)])]
        (d/transact! conn [(:tx session-tx)])
        {:status 302
         :session (dissoc session :return-to :user)
         :headers {"Location" (post-auth-location session)}
         :cookies {auth-session-cookie-name (session-cookie req (:session-id session-tx))}})
      {:status 302
       :session (assoc session :flash {:error "Invalid credentials"})
       :headers {"Location" "/auth/login"}})))

(defn logout-handler [{:keys [session biff.datalevin/conn] :as req}]
  (let [session-id (request-session-id req)]
    (when session-id
      (d/transact! conn [[:db.fn/retractEntity [:session/id session-id]]]))
    {:status 302
     :session (dissoc session :user)
     :headers {"Location" "/"}
     :cookies {auth-session-cookie-name (clear-session-cookie req)}}))

(defn require-auth [handler]
  (fn [req]
    (if (:user req)
      (handler req)
      {:status 302 :headers {"Location" "/auth/login"} :session (assoc (:session req) :flash {:error "Login required"})})))

;; =============================================================================
;; Email Verification
;; =============================================================================

(defn verify-email-handler [{:keys [params biff.datalevin/conn session] :as req}]
  (let [token (param params "token")
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

(defn github-login-handler [req]
  (oauth-login-response req {:client-id (:github-client-id req)
                             :client-secret (:github-client-secret req)
                             :callback-path "/auth/github/callback"
                             :state-key :github-oauth-state
                             :authorize-url-fn auth/github-authorize-url
                             :provider-name "GitHub"}))

(defn- github-email
  [access-token gh-user]
  (or (try
        (auth/github-primary-email (auth/github-get-emails access-token))
        (catch Exception e
          (log/warn e "GitHub email lookup failed")
          nil))
      (blank->nil (:email gh-user))))

(defn- github-oauth-user!
  [conn db access-token]
  (let [gh-user (auth/github-get-user access-token)
        email (github-email access-token gh-user)
        profile {:email email
                 :username-base (or (:login gh-user) email)
                 :avatar-url (:avatar_url gh-user)
                 :email-verified? (boolean email)
                 :update-attrs {:user/github-username (:login gh-user)}}
        provider-attrs {:user/github-id (:id gh-user)}
        base-tx (assoc (auth/github-create-user-tx gh-user)
                       :user/email email)]
    (find-or-create-oauth-user! conn db {:provider-id (:id gh-user)
                                         :find-by-provider auth/find-user-by-github-id
                                         :base-tx base-tx
                                         :provider-attrs provider-attrs
                                         :profile profile})))

(defn github-callback-handler [{:keys [params session biff.datalevin/conn admin-emails] :as req}]
  (let [code (param params "code")
        state (param params "state")
        expected-state (:github-oauth-state session)
        client-id (:github-client-id req)
        client-secret (:github-client-secret req)
        base-url (:base-url req)
        redirect-uri (str base-url "/auth/github/callback")]
    (if (or (empty? code) (empty? state) (not= state expected-state))
      (oauth-invalid-state-response session :github-oauth-state)
      (try
        (let [token-resp (auth/github-exchange-code {:client-id client-id
                                                     :client-secret client-secret
                                                     :code code
                                                     :redirect-uri redirect-uri})
              access-token (:access_token token-resp)
              db (d/db conn)
              user (github-oauth-user! conn db access-token)
              user (ensure-user-role! conn admin-emails user)
              req (assoc req :biff.datalevin/conn conn)]
          (login-session-response req session user :github-oauth-state))
        (catch Exception e
          (log/error "GitHub OAuth error:" (.getMessage e) (pr-str (class e)))
          (oauth-failure-response session :github-oauth-state "GitHub"))))))

;; =============================================================================
;; Google OAuth
;; =============================================================================

(defn google-login-handler [req]
  (oauth-login-response req {:client-id (:google-client-id req)
                             :client-secret (:google-client-secret req)
                             :callback-path "/auth/google/callback"
                             :state-key :google-oauth-state
                             :authorize-url-fn auth/google-authorize-url
                             :provider-name "Google"}))

(defn- google-oauth-user!
  [conn db access-token]
  (let [google-user (auth/google-get-user access-token)
        email (when (true? (:email_verified google-user))
                (blank->nil (:email google-user)))
        profile {:email email
                 :username-base (or email (:name google-user) (:sub google-user))
                 :avatar-url (:picture google-user)
                 :email-verified? (boolean email)}
        provider-attrs {:user/google-id (:sub google-user)}
        base-tx (assoc (auth/google-create-user-tx google-user)
                       :user/email email)]
    (find-or-create-oauth-user! conn db {:provider-id (:sub google-user)
                                         :find-by-provider auth/find-user-by-google-id
                                         :base-tx base-tx
                                         :provider-attrs provider-attrs
                                         :profile profile})))

(defn google-callback-handler [{:keys [params session biff.datalevin/conn admin-emails] :as req}]
  (let [code (param params "code")
        state (param params "state")
        expected-state (:google-oauth-state session)
        client-id (:google-client-id req)
        client-secret (:google-client-secret req)
        base-url (:base-url req)
        redirect-uri (str base-url "/auth/google/callback")]
    (if (or (empty? code) (empty? state) (not= state expected-state))
      (oauth-invalid-state-response session :google-oauth-state)
      (try
        (let [token-resp (auth/google-exchange-code {:client-id client-id
                                                     :client-secret client-secret
                                                     :code code
                                                     :redirect-uri redirect-uri})
              access-token (:access_token token-resp)
              db (d/db conn)
              user (google-oauth-user! conn db access-token)
              user (ensure-user-role! conn admin-emails user)
              req (assoc req :biff.datalevin/conn conn)]
          (login-session-response req session user :google-oauth-state))
        (catch Exception e
          (log/error "Google OAuth error:" (.getMessage e) (pr-str (class e)))
          (oauth-failure-response session :google-oauth-state "Google"))))))

;; =============================================================================
;; Password Reset
;; =============================================================================

(defn forgot-password-handler [{:keys [params session biff.datalevin/conn] :as req}]
  (let [email (str/trim (param params "email"))
        db (d/db conn)
        base-url (:base-url req)
        ;; Always show same message to prevent email enumeration
        success-msg "If an account with that email exists, a reset link has been sent."]
    (when-let [user (auth/find-user-by-email db email)]
      (let [token (str (UUID/randomUUID))
            expires-at (Date. (+ (System/currentTimeMillis) (* 1 60 60 1000))) ;; 1 hour
            reset-tx {:password-reset/token token
                      :password-reset/user [:user/id (:user/id user)]
                      :password-reset/expires-at expires-at}
            reset-url (reset-password-url base-url token)]
        (d/transact! conn [reset-tx])
        (try
          (mail/send-password-reset-email! req {:to email
                                                :reset-url reset-url})
          (catch Exception e
            (log/error e
                       "Password reset email delivery failed; retracting reset token and preserving generic anti-enumeration response"
                       {:email email
                        :user-id (:user/id user)
                        :token-retracted? true})
            (retract-lookup-refs! conn [[:password-reset/token token]])))))
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

(defn reset-password-handler [{:keys [params session biff.datalevin/conn] :as req}]
  (let [token (param params "token")
        password (param params "password")
        confirm-password (param params "confirm-password")
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
