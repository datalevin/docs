(ns datalevin.docs.util
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

(def ^:const site-name
  "Datalevin Docs")

(def ^:const site-description
  "Datalevin documentation, book chapters, and examples for Datalog, LMDB-backed storage, search, vectors, and production deployment.")

(def ^:const max-example-code-length 20000)

(def ^:const max-form-content-size-bytes
  (* 256 1024))

(def example-code-help-text
  (str "Max " max-example-code-length " characters."))

(def example-code-error-text
  (str "Code must be " max-example-code-length " characters or fewer."))

(defonce ^:private asset-version-cache
  (atom {}))

(defn- compute-asset-version
  [path]
  (when-let [resource (io/resource (str "public" path))]
    (let [digest (MessageDigest/getInstance "SHA-1")
          buffer (byte-array 8192)]
      (with-open [in (io/input-stream resource)]
        (loop []
          (let [read (.read in buffer)]
            (when (pos? read)
              (.update digest buffer 0 read)
              (recur)))))
      (let [bytes (.digest digest)]
        (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))))

(defn asset-url
  [path]
  (if (str/includes? (or path "") "?")
    path
    (if-let [version (or (get @asset-version-cache path)
                         (when-let [computed (compute-asset-version path)]
                           (get (swap! asset-version-cache assoc path computed) path)))]
      (str path "?v=" (subs version 0 12))
      path)))

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

(defn page-title
  [title]
  (let [title (str/trim (or title ""))]
    (cond
      (str/blank? title) site-name
      (str/includes? title site-name) title
      :else (str title " | " site-name))))

(defn strip-html
  [s]
  (-> (or s "")
      (str/replace #"(?is)<script[^>]*>.*?</script>" " ")
      (str/replace #"(?is)<style[^>]*>.*?</style>" " ")
      (str/replace #"<[^>]+>" " ")
      (str/replace #"&nbsp;" " ")
      (str/replace #"\s+" " ")
      (str/trim)))

(defn summarize-text
  [s max-len]
  (let [text (-> (or s "")
                 (str/replace #"\s+" " ")
                 (str/trim))]
    (when (seq text)
      (if (> (count text) max-len)
        (str (str/trim (subs text 0 max-len)) "…")
        text))))

(defn summarize-html
  [html max-len]
  (some-> html strip-html (summarize-text max-len)))

(defn request-origin
  [{:keys [base-url scheme server-name server-port headers]}]
  (or (some-> base-url
              (str/replace #"/+$" ""))
      (let [proto (or (get headers "x-forwarded-proto")
                      (some-> scheme name)
                      "https")
            host (or (get headers "x-forwarded-host")
                     (get headers "host")
                     (when (seq server-name)
                       (let [default-port (case proto
                                            "http" 80
                                            "https" 443
                                            nil)]
                         (if (and server-port default-port (not= server-port default-port))
                           (str server-name ":" server-port)
                           server-name))))]
        (when (seq host)
          (str proto "://" host)))))

(defn absolute-url
  [req path]
  (when-let [origin (request-origin req)]
    (let [path (if (str/starts-with? (or path "") "/")
                 path
                 (str "/" path))]
      (str origin path))))
