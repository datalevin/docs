(ns datalevin.docs.handlers.admin-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.docs.handlers.admin :as admin]))

(deftest wrap-require-admin-or-reindex-secret-behavior
  (let [handler (admin/wrap-require-admin-or-reindex-secret
                 (fn [_]
                   {:status 204}))]
    (testing "allows admins"
      (is (= 204 (:status (handler {:user {:user/role :admin}})))))

    (testing "allows requests with the configured reindex secret"
      (is (= 204
             (:status (handler {:headers {"x-reindex-secret" "expected"}
                                :reindex-secret "expected"})))))

    (testing "rejects requests without admin role or matching secret"
      (is (= 403 (:status (handler {:user {:user/role :user}
                                    :headers {"x-reindex-secret" "wrong"}
                                    :reindex-secret "expected"}))))
      (is (= 403 (:status (handler {})))))))
