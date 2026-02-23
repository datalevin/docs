(ns datalevin.docs.handlers.examples
  (:require [datalevin.core :as d]
            [clojure.string :as str]
            [ring.middleware.anti-forgery :as anti-forgery]
            [datalevin.docs.views.layout :as layout])
  (:import [java.util Date UUID]))

(defn create-example-handler [{:keys [params session biff.datalevin/conn] :as req}]
  (let [user (:user session)
        title (str/trim (get params "title" ""))
        code (str/trim (get params "code" ""))
        output (str/trim (get params "output" ""))
        doc-section (get params "doc-section" "")]
    (cond
      (not user)
      {:status 302 :headers {"Location" "/auth/login"} :session (assoc session :flash {:error "Please log in"})}
      (empty? title)
      {:status 302 :headers {"Location" "/examples/new"} :session (assoc session :flash {:error "Title required"})}
      (empty? code)
      {:status 302 :headers {"Location" "/examples/new"} :session (assoc session :flash {:error "Code required"})}
      :else
      (let [example-tx {:example/id (UUID/randomUUID) :example/title title :example/code code
                        :example/output output :example/doc-section doc-section
                        :example/author [:user/id (:user/id user)] :example/created-at (Date.)}]
        (d/transact! conn [example-tx])
        {:status 302 :headers {"Location" (str "/docs/" doc-section)}
         :session (assoc session :flash {:success "Example submitted!"})})))

(defn new-example-form [{:keys [params session] :as req}]
  (let [user (:user session)
        flash (:flash session)
        doc-section (get params "doc-section" "")
        token (force anti-forgery/*anti-forgery-token*)
        error-msg (:error flash)
        success-msg (:success flash)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (layout/base "Submit Example"
              [:div
               [:h1 "Submit Example"]
               (when error-msg [:p error-msg])
               (when success-msg [:p success-msg])
               [:form {:method "post" :action "/examples"}
                [:input {:type "hidden" :name "__anti-forgery-token" :value token}]
                [:input {:type "hidden" :name "doc-section" :value doc-section}]
                [:div [:label "Title"] [:input {:name "title" :required true}]]
                [:div [:label "Code"] [:textarea {:name "code" :required true}]]
                [:div [:label "Output (optional)"] [:textarea {:name "output"}]]
                [:button "Submit"]]])})))
