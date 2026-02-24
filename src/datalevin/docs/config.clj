(ns datalevin.docs.config)

(defn resolve-env
  "Custom resolver for environment variables"
  [opts]
  {:port (Integer/parseInt (or (System/getenv "PORT") "3000"))
   :db-path (or (System/getenv "DB_PATH") "data/dev-db")
   :base-url (or (System/getenv "BASE_URL") "http://localhost:3000")
   :session-secret (System/getenv "SESSION_SECRET")
   :env (or (System/getenv "ENV") "dev")
   :github-client-id (System/getenv "GITHUB_CLIENT_ID")
   :github-client-secret (System/getenv "GITHUB_CLIENT_SECRET")
   :reindex-secret (System/getenv "REINDEX_SECRET")})
