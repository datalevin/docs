(ns datalevin.docs.handlers.examples-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datalevin.docs.handlers.examples :as examples]
            [datalevin.docs.util :as util]
            [ring.middleware.anti-forgery :as anti-forgery]))

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
                       :user {:user/id (java.util.UUID/randomUUID)}
                       :session {}
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
                     :user {:user/id (java.util.UUID/randomUUID)}
                     :session {}
                     :biff.datalevin/conn ::conn}))]
    (is (= 302 (:status response)))
    (is (= code
           (get-in (first @txs) [0 :example/code])))))

(deftest create-example-handler-rejects-invalid-doc-section
  (let [transacted? (atom false)
        response (with-redefs [datalevin.core/transact!
                               (fn [& _]
                                 (reset! transacted? true))]
                   (examples/create-example-handler
                    {:params {"code" "(println :ok)"
                              "doc-section" "//evil.com"}
                     :user {:user/id (java.util.UUID/randomUUID)}
                     :session {}
                     :biff.datalevin/conn ::conn}))]
    (is (= 302 (:status response)))
    (is (= "/examples/new"
           (get-in response [:headers "Location"])))
    (is (= "Invalid documentation section"
           (get-in response [:session :flash :error])))
    (is (false? @transacted?))))

(deftest create-example-handler-redirects-to-examples-when-doc-section-is-blank
  (let [txs (atom [])
        response (with-redefs [datalevin.core/transact!
                               (fn [_ tx]
                                 (swap! txs conj tx))]
                   (examples/create-example-handler
                    {:params {"code" "(println :ok)"
                              "doc-section" "   "}
                     :user {:user/id (java.util.UUID/randomUUID)}
                     :session {}
                     :biff.datalevin/conn ::conn}))]
    (is (= 302 (:status response)))
    (is (= "/examples"
           (get-in response [:headers "Location"])))
    (is (= ""
           (get-in (first @txs) [0 :example/doc-section])))))

(deftest new-example-form-renders-request-aware-layout
  (binding [anti-forgery/*anti-forgery-token* (delay "test-token")]
    (let [resp (examples/new-example-form
                {:params {}
                 :session {:flash {:success "Saved"}}
                 :user {:user/username "alice"}})
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (str/includes? body "alice"))
      (is (= 1 (count (re-seq #"Saved" body)))))))
