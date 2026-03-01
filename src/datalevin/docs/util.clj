(ns datalevin.docs.util)

(defn escape-html
  "Escapes HTML special characters to prevent XSS.
   Uses single pass with replaceAll for better performance."
  [s]
  (when s
    (-> (str s)
        ;; Order matters: & must be first to avoid double-escaping
        (clojure.string/replace "&" "&amp;")
        (clojure.string/replace "<" "&lt;")
        (clojure.string/replace ">" "&gt;")
        (clojure.string/replace "\"" "&quot;")
        (clojure.string/replace "'" "&#x27;"))))
