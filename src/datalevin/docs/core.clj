(ns datalevin.docs.core
  (:require [biff.datalevin.core :as biff]
            [biff.datalevin.session :as session]
            [datalevin.docs.config :as config]
            [datalevin.docs.schema :as schema]
            [datalevin.docs.routes :as routes]
            [datalevin.docs.handlers.search :as search]
            [datalevin.core :as d]
            [taoensso.timbre :as log]
            [ring.adapter.jetty :as jetty])
  (:import [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

(defn configure-logging!
  "Configures Timbre logging based on environment."
  [env]
  (log/merge-config!
    {:min-level (if (= env "prod") :info :debug)
     :output-fn (fn [{:keys [level msg_ timestamp_ ?ns-str]}]
                  (str (force timestamp_) " "
                       (clojure.string/upper-case (name level)) " "
                       (or ?ns-str "?") " — "
                       (force msg_)))
     :timestamp-opts {:pattern "yyyy-MM-dd HH:mm:ss"
                      :timezone :utc}}))

(def system nil)

(defn start-session-cleanup
  "Starts a scheduled job that purges expired sessions every hour.
   Returns the ScheduledExecutorService (call .shutdown to stop)."
  [conn]
  (let [scheduler (Executors/newSingleThreadScheduledExecutor)]
    (.scheduleAtFixedRate
      scheduler
      (fn []
        (try
          (let [txs (session/cleanup-expired-sessions-tx conn)]
            (when (seq txs)
              (d/transact! conn txs)
              (log/info "Session cleanup: removed" (count txs) "expired sessions")))
          (catch Exception e
            (log/error e "Session cleanup failed"))))
      1   ;; initial delay (hours)
      1   ;; period (hours)
      TimeUnit/HOURS)
    scheduler))

(def doc-schema
  {:doc/title {:db/fulltext true}
   :doc/chapter {}
   :doc/part {}
   :doc/content {:db/fulltext true}
   :doc/filename {:db/unique :db.unique/identity}})

(defn start
  "Starts the system. Accepts an optional map:
     :wrap-handler — fn that wraps the final Ring handler (e.g. for dev live-reload)
     :dev?        — when true, rebuilds the router on every request so code changes
                    take effect without restart"
  ([] (start {}))
  ([{:keys [wrap-handler dev?]}]
   (let [cfg (config/resolve-env {})
         _ (configure-logging! (:env cfg))
         db-path (:db-path cfg)

         ;; Single DB with all schemas
         all-schema (merge schema/user-schema schema/example-schema doc-schema)
         conn (d/get-conn db-path all-schema)

         session-scheduler (start-session-cleanup conn)

         sys (biff/start-system
               {:biff.datalevin/db-path db-path
                :biff.datalevin/schema all-schema
                :biff.datalevin/conn conn
                :port (:port cfg)
                :base-url (:base-url cfg)
                :session-secret (or (:session-secret cfg) "dev-secret-min-32-chars")
                :github-client-id (:github-client-id cfg)
                :github-client-secret (:github-client-secret cfg)
                :admin-emails (:admin-emails cfg)
                :reindex-secret (:reindex-secret cfg)
                :session-scheduler session-scheduler
                :biff/config cfg}
               [biff/use-datalevin
                (fn [sys]
                  (let [app (if dev?
                              ;; Rebuild router per request so code changes apply immediately
                              (fn [req] ((routes/app sys) req))
                              (routes/app sys))
                        app (cond-> app wrap-handler wrap-handler)
                        jetty-server (do (log/info "Starting server on port" (:port sys))
                                         (jetty/run-jetty app {:port (:port sys) :join? false}))]
                    (assoc sys :jetty/server jetty-server)))])]
     (log/info "System started with auth, search, and session cleanup")
     sys)))

(defn -main [& args]
  (start))

(comment
  (def sys (start))
  (require '[biff.datalevin.core :as biff])
  (biff/stop-system sys))
