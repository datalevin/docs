(ns datalevin.docs.util)

(def ^:const max-example-code-length 20000)

(def ^:const max-form-content-size-bytes
  (* 256 1024))

(def example-code-help-text
  (str "Max " max-example-code-length " characters."))

(def example-code-error-text
  (str "Code must be " max-example-code-length " characters or fewer."))

(defn escape-html
  "Escapes HTML special characters to prevent XSS.
   Single-pass scan via reduce over chars for better performance."
  [s]
  (when s
    (let [input (str s)
          sb (StringBuilder. (count input))]
      (dotimes [i (count input)]
        (let [c (.charAt input i)]
          (case c
            \& (.append sb "&amp;")
            \< (.append sb "&lt;")
            \> (.append sb "&gt;")
            \" (.append sb "&quot;")
            \' (.append sb "&#x27;")
            (.append sb c))))
      (.toString sb))))
