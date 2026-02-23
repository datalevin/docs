(ns datalevin.docs.tasks.pdf
  (:require [datalevin.docs.views.layout :as layout]))

(defn pdf-handler [{:keys [session] :as req}]
  (if (:user session)
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (layout/base "PDF Download" 
              [:div [:h1 "PDF Download"] 
               [:p "PDF generation coming soon"]])}
    {:status 302 :headers {"Location" "/auth/login"}}))
