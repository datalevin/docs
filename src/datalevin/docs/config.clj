(ns datalevin.docs.config
  (:require [clojure.string :as str]))

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
