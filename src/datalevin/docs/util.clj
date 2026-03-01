(ns datalevin.docs.util)

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
