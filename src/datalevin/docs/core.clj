(ns datalevin.docs.core
  (:require [biff.datalevin.core :as biff]
            [datalevin.docs.config :as config]
            [datalevin.docs.schema :as schema]
            [datalevin.docs.routes :as routes]
            [datalevin.docs.handlers.search :as search]
            [datalevin.core :as d]
            [taoensso.timbre :as log]
            [ring.adapter.jetty :as jetty]))

(def system nil)

(def search-schema
  {:doc/title {}
   :doc/chapter {}
   :doc/part {}
   :doc/content {}
   :doc/filename {}})

(defn start []
  (let [cfg (config/resolve-env {})
        db-path (:db-path cfg)
        
        ;; Main app DB with user/session/example schema
        app-conn (d/get-conn db-path (merge schema/user-schema schema/example-schema))
        
        ;; Search DB with doc schema
        search-db-path "data/docs-db"
        search-conn (d/get-conn search-db-path search-schema)
        
        sys (biff/start-system
               {:biff.datalevin/db-path db-path
                :biff.datalevin/schema (merge schema/user-schema schema/example-schema)
               :biff.datalevin/conn app-conn
               :port (:port cfg)
               :base-url (:base-url cfg)
               :session-secret (or (:session-secret cfg) "dev-secret-min-32-chars")
               :search/conn search-conn}
              [biff/use-datalevin
               (fn [sys]
                 (let [app (routes/app sys)]
                   (log/info "Starting server on port" (:port sys))
                   (jetty/run-jetty app {:port (:port sys) :join? false})
                   (assoc sys :server app)))])]
    (log/info "System started with auth and search")
    sys))

(defn -main [& args]
  (start))

(comment
  (def sys (start))
  (require '[biff.datalevin.core :as biff])
  (biff/stop-system sys))
