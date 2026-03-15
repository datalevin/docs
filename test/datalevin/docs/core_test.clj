(ns datalevin.docs.core-test
  (:require [biff.datalevin.core :as biff]
            [clojure.test :refer [deftest is]]
            [datalevin.docs.core :as core]))

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
