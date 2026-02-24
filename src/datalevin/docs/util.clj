(ns datalevin.docs.util
  (:require [clojure.string :as str]))

(defn escape-html
  "Escapes HTML special characters to prevent XSS."
  [s]
  (when s
    (-> (str s)
        (str/replace "&" "&amp;")
        (str/replace "<" "&lt;")
        (str/replace ">" "&gt;")
        (str/replace "\"" "&quot;")
        (str/replace "'" "&#x27;"))))
