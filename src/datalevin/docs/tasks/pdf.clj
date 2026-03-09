(ns datalevin.docs.tasks.pdf
  (:require [clojure.java.io :as jio]))

(def pdf-filename "datalevin-book.pdf")

(defn pdf-handler [req]
  (let [pdf-file (jio/file "resources/public" pdf-filename)]
    (if (.exists pdf-file)
      {:status 200
       :headers {"Content-Type" "application/pdf"
                 "Content-Disposition" (str "attachment; filename=\"" pdf-filename "\"")
                 "Content-Length" (str (.length pdf-file))}
       :body (jio/input-stream pdf-file)}
      {:status 404
       :headers {"Content-Type" "text/plain"}
       :body "PDF not found. Please add datalevin-book.pdf to resources/public/"})))
