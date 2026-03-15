(ns datalevin.docs.middleware.rate-limit-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.docs.middleware.rate-limit :as rate-limit]))

(defn- request [ip]
  {:headers {}
   :remote-addr ip})

(deftest forgot-and-reset-password-have-independent-rate-limits
  (reset! @#'rate-limit/state {})
  (let [forgot-handler (rate-limit/wrap-forgot-password-rate-limit
                        (fn [_] {:status 200}))
        reset-handler (rate-limit/wrap-reset-password-rate-limit
                       (fn [_] {:status 200}))
        req (request "203.0.113.10")]
    (testing "forgot-password attempts do not consume reset-password budget"
      (is (= [200 200 200 429]
             (mapv :status (repeatedly 4 #(forgot-handler req)))))
      (is (= [200 200 200 429]
             (mapv :status (repeatedly 4 #(reset-handler req))))))))
