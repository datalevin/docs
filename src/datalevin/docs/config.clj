(ns datalevin.docs.config
  (:require [clojure.string :as str]))

(def ^:const dev-session-secret
  "dev-session-secret-fallback-1234")

(defn env
  "Returns the value of an environment variable, falling back to system property."
  [name]
  (or (System/getenv name)
      (System/getProperty name)))

(defn- parse-int
  [name value]
  (when-not (str/blank? value)
    (try
      (Integer/parseInt value)
      (catch NumberFormatException _
        (throw (ex-info (str name " must be an integer")
                        {:env-var name
                         :value value})))))) 

(defn- parse-bool
  [value]
  (when-not (str/blank? value)
    (contains? #{"1" "true" "yes" "on"}
               (str/lower-case (str/trim value)))))

(defn- blank->nil
  [value]
  (when-not (str/blank? value)
    value))

(defn resolve-env
  "Custom resolver for environment variables"
  [opts]
  {:port (Integer/parseInt (or (env "PORT") "3000"))
   :db-path (or (env "DB_PATH") "data/dev-db")
   :base-url (or (env "BASE_URL") "http://localhost:3000")
   :session-secret (env "SESSION_SECRET")
   :env (or (env "ENV") "dev")
   :github-client-id (env "GITHUB_CLIENT_ID")
   :github-client-secret (env "GITHUB_CLIENT_SECRET")
   :admin-emails (when-let [s (env "ADMIN_EMAILS")]
                   (into #{} (map str/trim) (str/split s #",")))
   :reindex-secret (env "REINDEX_SECRET")
   :mail-from (env "MAIL_FROM")
   :smtp-host (env "SMTP_HOST")
   :smtp-port (parse-int "SMTP_PORT" (env "SMTP_PORT"))
   :smtp-user (env "SMTP_USER")
   :smtp-pass (env "SMTP_PASS")
   :smtp-ssl (parse-bool (env "SMTP_SSL"))
   :smtp-tls (parse-bool (env "SMTP_TLS"))
   :smtp-debug (parse-bool (env "SMTP_DEBUG"))})

(defn session-secret
  "Returns the configured session secret.
   In production, SESSION_SECRET must be set explicitly."
  [{:keys [env session-secret]}]
  (if (str/blank? session-secret)
    (if (= env "prod")
      (throw (ex-info "SESSION_SECRET must be set when ENV=prod"
                      {:env env}))
      dev-session-secret)
    session-secret))

(defn mail-config
  "Returns normalized mail delivery config.
   In production, SMTP_HOST and MAIL_FROM must be set explicitly."
  [{:keys [env mail-from smtp-host smtp-port smtp-user smtp-pass smtp-ssl smtp-tls smtp-debug]}]
  (let [mail-from (blank->nil mail-from)
        smtp-host (blank->nil smtp-host)
        smtp-user (blank->nil smtp-user)
        smtp-pass (blank->nil smtp-pass)
        supplied? (some some? [mail-from smtp-host smtp-port smtp-user smtp-pass smtp-ssl smtp-tls smtp-debug])
        missing (cond-> []
                  (nil? mail-from) (conj "MAIL_FROM")
                  (nil? smtp-host) (conj "SMTP_HOST"))]
    (cond
      (and (not= env "prod") (not supplied?))
      nil

      (seq missing)
      (throw (ex-info (str "Email delivery requires " (str/join " and " missing))
                      {:env env
                       :missing missing}))

      :else
      {:from mail-from
       :server (cond-> {:host smtp-host}
                 smtp-port (assoc :port smtp-port)
                 smtp-user (assoc :user smtp-user)
                 smtp-pass (assoc :pass smtp-pass)
                 smtp-ssl (assoc :ssl true)
                 smtp-tls (assoc :tls true)
                 smtp-debug (assoc :debug true))})))
