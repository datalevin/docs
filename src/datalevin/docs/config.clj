(ns datalevin.docs.config
  (:require [clojure.string :as str]))

(def ^:const dev-session-secret
  "dev-secret-min-32-chars")

(defn env
  "Returns the value of an environment variable, falling back to system property."
  [name]
  (or (System/getenv name)
      (System/getProperty name)))

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
   :reindex-secret (env "REINDEX_SECRET")})

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
