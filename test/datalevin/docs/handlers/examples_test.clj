(ns datalevin.docs.handlers.examples-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.docs.handlers.examples :as examples]
            [datalevin.docs.util :as util]))

(deftest example-queries-use-plain-pull-spec-inputs
  (is (= '[:find [(pull ?e pull-spec) ...]
           :in $ pull-spec
           :where
           [?e :example/id]
           [?e :example/removed? false]]
         @#'examples/list-examples-query))
  (is (= '[:find (pull ?e pull-spec) .
           :in $ ?id pull-spec
           :where
           [?e :example/id ?id]
           [?e :example/removed? false]]
         @#'examples/example-by-id-query))
  (is (= '[:find [(pull ?e pull-spec) ...]
           :in $ ?uid pull-spec
           :where
           [?e :example/author ?uid]
           [?e :example/removed? false]]
         @#'examples/examples-by-author-query)))

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

(deftest create-example-handler-stores-raw-code
  (let [txs (atom [])
        code "<tag attr=\"v\">& body</tag>"
        response (with-redefs [datalevin.core/transact!
                               (fn [_ tx]
                                 (swap! txs conj tx))]
                   (examples/create-example-handler
                    {:params {"code" code
                              "doc-section" "01-why-datalevin"}
                     :session {:user {:user/id (java.util.UUID/randomUUID)}}
                     :biff.datalevin/conn ::conn}))]
    (is (= 302 (:status response)))
    (is (= code
           (get-in (first @txs) [0 :example/code])))))
