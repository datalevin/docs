(ns datalevin.docs.handlers.examples-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.docs.handlers.examples :as examples]
            [datalevin.docs.util :as util]))

(deftest create-example-handler-rejects-oversized-code
  (testing "oversized code is rejected before any transact"
    (let [transacted? (atom false)
          response (with-redefs [datalevin.core/transact!
                                 (fn [& _]
                                   (reset! transacted? true))]
                     (examples/create-example-handler
                      {:params {"code" (apply str (repeat (inc util/max-example-code-length) "x"))
                                "doc-section" "01-why-datalevin"}
                       :session {:user {:user/id (java.util.UUID/randomUUID)}}
                       :biff.datalevin/conn ::conn}))]
      (is (= 302 (:status response)))
      (is (= "/docs/01-why-datalevin"
             (get-in response [:headers "Location"])))
      (is (= util/example-code-error-text
             (get-in response [:session :flash :error])))
      (is (false? @transacted?)))))
