(ns datalevin.docs.core-test
  (:require [biff.datalevin.core :as biff]
            [clojure.test :refer [deftest is]]
            [datalevin.docs.core :as core]))

(deftest start-configures-jetty-graceful-stop-timeout
  (let [jetty-server (atom nil)]
    (with-redefs [core/configure-logging! (fn [_])
                  core/start-session-cleanup (fn [_] ::scheduler)
                  biff/start-system (fn [initial-system components]
                                      ((second components) (assoc initial-system :biff/stop [])))
                  core/stop-session-cleanup (fn [_] nil)
                  ring.adapter.jetty/run-jetty (fn [_ opts]
                                                 (let [server (org.eclipse.jetty.server.Server.)]
                                                   ((:configurator opts) server)
                                                   (reset! jetty-server server)
                                                   server))
                  datalevin.docs.config/resolve-env (fn [_]
                                                      {:env "test"
                                                       :port 8080
                                                       :db-path "data/test-db"})
                  datalevin.docs.config/session-secret (fn [_] "test-secret")
                  datalevin.docs.config/mail-config (fn [_] nil)
                  datalevin.docs.handlers.pages/warm-static-caches! (fn [] nil)
                  datalevin.core/get-conn (fn [& _] ::conn)]
      (core/start)
      (is (= 30000 (.getStopTimeout ^org.eclipse.jetty.server.Server @jetty-server))))))

(deftest shutdown-hook-stops-system
  (let [stopped (atom nil)
        sys {:name :docs}]
    (with-redefs [biff/stop-system (fn [arg]
                                     (reset! stopped arg))]
      (.run ^Thread (#'core/shutdown-hook-thread sys))
      (is (= sys @stopped)))))

(deftest main-installs-shutdown-hook
  (let [hooked (atom nil)]
    (with-redefs [core/start (fn [& _] ::sys)
                  core/install-shutdown-hook! (fn [sys]
                                                (reset! hooked sys))]
      (core/-main)
      (is (= ::sys @hooked)))))
