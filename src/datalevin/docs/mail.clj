(ns datalevin.docs.mail
  (:require [postal.core :as postal]
            [taoensso.timbre :as log]))

(defn send!
  [{:keys [mail-config] :as _req} {:keys [to subject body]}]
  (if-let [{:keys [from server]} mail-config]
    (let [result (postal/send-message server {:from from
                                              :to to
                                              :subject subject
                                              :body body})]
      (when-not (= 0 (:code result))
        (throw (ex-info "Email delivery failed"
                        {:to to
                         :subject subject
                         :result result})))
      result)
    (do
      (log/info "=== Development email ==="
                {:to to
                 :subject subject
                 :body body})
      {:code 0 :error :LOGGED :message "message logged"})))

(defn send-verification-email!
  [req {:keys [to username verify-url]}]
  (send! req
         {:to to
          :subject "Verify your Datalevin Docs account"
          :body (str "Hi " (or username "there") ",\n\n"
                     "Verify your Datalevin Docs account by opening this link:\n\n"
                     verify-url
                     "\n\nIf you did not create this account, you can ignore this email.\n")}))

(defn send-password-reset-email!
  [req {:keys [to reset-url]}]
  (send! req
         {:to to
          :subject "Reset your Datalevin Docs password"
          :body (str "Use this link to reset your Datalevin Docs password:\n\n"
                     reset-url
                     "\n\nThis link expires in 1 hour. If you did not request a reset, you can ignore this email.\n")}))
