(ns datalevin.docs.handlers.auth
  (:require [biff.datalevin.auth :as auth]
            [biff.datalevin.session :as session]
            [datalevin.core :as d]
            [clojure.string :as str])
  (:import [java.util Date UUID]))

(defn register-handler [{:keys [params session biff.datalevin/conn] :as req}]
  (let [email (str/trim (get params "email" ""))
        username (str/trim (get params "username" ""))
        password (get params "password" "")
        confirm-password (get params "confirm-password" "")
        db (d/db conn)]
    (if (or (empty? email) (empty? username) (empty? password))
      {:status 400 :session (assoc session :flash {:error "All fields required"}) :headers {"Location" "/auth/register"}}
      (if (not (= password confirm-password))
        {:status 400 :session (assoc session :flash {:error "Passwords mismatch"}) :headers {"Location" "/auth/register"}}
        (if (< (count password) 8)
          {:status 400 :session (assoc session :flash {:error "Password too short"}) :headers {"Location" "/auth/register"}}
          (let [user-id (UUID/randomUUID)
                user-tx {:user/id user-id :user/email email :user/username username
                         :user/password-hash (auth/hash-password password)
                         :user/role :user :user/email-verified? true :user/created-at (Date.)}
                session-tx (session/create-session [:user/id user-id])]
            (d/transact! conn [user-tx (:tx session-tx)])
            {:status 302
             :session (assoc session :user (dissoc user-tx :user/password-hash) :flash {:success "Welcome!"})
             :headers {"Location" "/"}
             :cookies {"session" {:value (str (:session-id session-tx))}}}))))))

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
