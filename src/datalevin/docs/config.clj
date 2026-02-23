(ns datalevin.docs.config
  (:require [aero.core :as aero]
            [clojure.java.io :as jio])
  (:import [java.io PushbackReader]))

(defn read-config
  "Read configuration from resources/config.edn"
  [opts]
  (aero.core/read-config (jio/reader (jio/resource "config.edn")) opts))

(defn resolve-env
  "Custom resolver for environment variables"
  [opts]
  {:port (Integer/parseInt (or (System/getenv "PORT") "3000"))
   :db-path (or (System/getenv "DB_PATH") "data/dev-db")
   :base-url (or (System/getenv "BASE_URL") "http://localhost:3000")
   :session-secret (System/getenv "SESSION_SECRET")
   :env (or (System/getenv "ENV") "dev")})
