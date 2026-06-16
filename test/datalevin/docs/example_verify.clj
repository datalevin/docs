(ns datalevin.docs.example-verify
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import [java.io PushbackReader StringReader]))

(def ^:private clojure-languages #{"clojure" "clj"})
(def ^:private valid-statuses #{:runnable :fragment :external :api-sketch})

(defn- markdown-files
  [docs-dir]
  (->> (file-seq (io/file docs-dir))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".md"))
       (sort-by #(.getPath %))))

(defn- fence-open
  [line]
  (when-let [[_ lang] (re-matches #"^\s*```\s*([A-Za-z0-9_-]+).*$" line)]
    (str/lower-case lang)))

(defn- fence-close?
  [line]
  (boolean (re-matches #"^\s*```\s*$" line)))

(defn- rel-path
  [root file]
  (let [root-path (.getCanonicalPath (io/file root))
        file-path (.getCanonicalPath file)]
    (subs file-path (inc (count root-path)))))

(defn- chapter-id
  [file]
  (str/replace (.getName file) #"\.md$" ""))

(defn- code-fences
  [docs-dir file]
  (let [lines (str/split-lines (slurp file))]
    (loop [remaining (map-indexed vector lines)
           current nil
           blocks []]
      (if-let [[[idx line] & more] (seq remaining)]
        (if current
          (if (fence-close? line)
            (recur more nil (conj blocks (assoc current :end-line (inc idx))))
            (recur more (update current :lines conj line) blocks))
          (if-let [lang (fence-open line)]
            (recur more
                   {:file (rel-path docs-dir file)
                    :chapter (chapter-id file)
                    :language lang
                    :start-line (inc idx)
                    :lines []}
                   blocks)
            (recur more nil blocks)))
        blocks))))

(defn- with-ids
  [blocks]
  (->> blocks
       (group-by :chapter)
       (mapcat
        (fn [[_ chapter-blocks]]
          (map-indexed
           (fn [idx block]
             (let [language (:language block)]
               (assoc block
                      :ordinal (inc idx)
                      :id (format "%s#%s-%03d"
                                  (:chapter block)
                                  language
                                  (inc idx))
                      :code (str/join "\n" (:lines block)))))
           chapter-blocks)))
       (sort-by (juxt :file :start-line))))

(defn extract-examples
  "Extract executable-language code fences from markdown docs."
  [{:keys [docs-dir languages]
    :or {docs-dir "resources/docs"
         languages clojure-languages}}]
  (->> (markdown-files docs-dir)
       (mapcat #(code-fences docs-dir %))
       (filter #(contains? languages (:language %)))
       with-ids))

(defn- read-manifest
  [manifest-path]
  (let [file (io/file manifest-path)]
    (if (.exists file)
      (edn/read-string (slurp file))
      {:examples {}})))

(defn- manifest-entry
  [manifest id]
  (get-in manifest [:examples id]))

(defn- example-status
  [manifest id]
  (or (:status (manifest-entry manifest id))
      :unclassified))

(defn- combine-code
  [& snippets]
  (let [snippets (->> snippets
                      (remove nil?)
                      (remove str/blank?))]
    (when (seq snippets)
      (str/join "\n" snippets))))

(defn- fixture
  [manifest fixture-id]
  (when fixture-id
    (or (get-in manifest [:fixtures fixture-id])
        (throw (ex-info "Unknown example fixture"
                        {:fixture fixture-id})))))

(defn- attach-manifest
  [manifest examples]
  (mapv (fn [example]
          (let [entry (manifest-entry manifest (:id example))
                status (example-status manifest (:id example))
                fixture-data (fixture manifest (:fixture entry))]
            (assoc example
                   :status status
                   :reason (:reason entry)
                   :fixture (:fixture entry)
                   :setup (combine-code (:setup fixture-data)
                                        (:setup entry))
                   :teardown (combine-code (:teardown entry)
                                           (:teardown fixture-data)))))
        examples))

(defn- status-counts
  [examples]
  (into (sorted-map)
        (frequencies (map :status examples))))

(defn- unknown-statuses
  [examples]
  (->> examples
       (map :status)
       set
       (remove #(or (= :unclassified %) (valid-statuses %)))
       sort
       vec))

(defn- print-inventory
  [examples]
  (println "Clojure example inventory")
  (println "  total:" (count examples))
  (println "  status counts:")
  (doseq [[status n] (status-counts examples)]
    (println "   " status n))
  (when-let [items (seq (filter #(= :unclassified (:status %)) examples))]
    (println "  first unclassified:")
    (doseq [{:keys [id file start-line]} (take 20 items)]
      (println "   " id (str file ":" start-line)))))

(defn- read-forms
  [code]
  (let [eof (Object.)]
    (with-open [reader (PushbackReader. (StringReader. code))]
      (loop [forms []]
        (let [form (read reader false eof)]
          (if (identical? eof form)
            forms
            (recur (conj forms form))))))))

(defn- safe-ns-part
  [s]
  (-> s
      (str/replace #"[^A-Za-z0-9_]+" "-")
      (str/replace #"^-+" "")
      (str/replace #"-+$" "")))

(defn- eval-code!
  [code]
  (doseq [form (read-forms code)]
    (eval form)))

(defn- eval-example!
  [{:keys [id code setup teardown]}]
  (let [ns-sym (symbol (str "datalevin.docs.example-verify.run."
                            (safe-ns-part id)))
        ns-obj (create-ns ns-sym)]
    (binding [*ns* ns-obj]
      (clojure.core/refer 'clojure.core)
      (when setup
        (eval-code! setup))
      (try
        (eval-code! code)
        (finally
          (when teardown
            (eval-code! teardown)))))))

(defn- run-runnable!
  [examples]
  (let [runnable (filter #(= :runnable (:status %)) examples)]
    (println "Running runnable Clojure examples:" (count runnable))
    (loop [[example & more] runnable
           results []]
      (if example
        (let [result (try
                       (eval-example! example)
                       {:id (:id example)
                        :status :pass}
                       (catch Throwable t
                         {:id (:id example)
                          :status :fail
                          :file (:file example)
                          :start-line (:start-line example)
                          :message (ex-message t)
                          :exception t}))]
          (println " " (:status result) (:id result))
          (recur more (conj results result)))
        results))))

(defn- failure-summary
  [results]
  (->> results
       (filter #(= :fail (:status %)))
       (map #(select-keys % [:id :file :start-line :message]))
       vec))

(defn- code-preview
  [code]
  (let [preview (-> code
                    (str/replace #"\s+" " ")
                    (str/trim))]
    (subs preview 0 (min 90 (count preview)))))

(defn- classify-example
  [{:keys [code file]}]
  (let [trimmed (str/trim code)
        lines (str/split-lines trimmed)]
    (cond
      (or (str/includes? trimmed "{{datalevin-version}}")
          (str/includes? trimmed "dtlv://")
          (str/includes? trimmed "datalevin.server")
          (str/includes? trimmed "new-client")
          (str/includes? trimmed "cl/new-client")
          (str/includes? trimmed "DATALEVIN_DEFAULT_PASSWORD")
          (str/includes? trimmed "/data/")
          (str/includes? trimmed "/backup/")
          (str/includes? trimmed "replica-host")
          (str/includes? trimmed "primary-host")
          (str/includes? trimmed "openai")
          (str/includes? trimmed "embedding"))
      {:status :external
       :reason "Requires a particular runtime, server, network service, credential, operator path, or external integration."}

      (or (str/includes? file "31-edn-format")
          (str/includes? file "28-datalog-schema-reference")
          (str/includes? file "29-key-value-api-reference")
          (str/includes? file "30-datalog-built-ins-reference")
          (str/includes? file "32-core-api-helpers-reference")
          (str/includes? file "33-client-api-reference")
          (str/starts-with? trimmed "{:deps")
          (str/starts-with? trimmed ":dependencies")
          (str/starts-with? trimmed "<dependency>")
          (str/starts-with? trimmed "[[")
          (str/starts-with? trimmed "[{")
          (str/starts-with? trimmed "{:")
          (str/starts-with? trimmed ":"))
      {:status :api-sketch
       :reason "Reference data, configuration, schema, or API surface snippet rather than a standalone executable example."}

      (or (some #(str/includes? % "...") lines)
          (str/includes? trimmed "[...]")
          (str/includes? trimmed ";; =>")
          (str/includes? trimmed ";=>")
          (str/includes? trimmed "=>")
          (str/includes? trimmed "schema")
          (str/includes? trimmed "conn")
          (str/includes? trimmed "db")
          (str/includes? trimmed "d/")
          (str/includes? trimmed "(q ")
          (str/includes? trimmed "(d/q ")
          (str/includes? trimmed "(defn ")
          (str/includes? trimmed "(def "))
      {:status :fragment
       :reason "Illustrative chapter code that depends on surrounding setup, prior snippets, expected-output comments, or fixtures."}

      :else
      {:status :fragment
       :reason "Conservative default until a maintainer marks this snippet runnable with a fixture."})))

(defn- manifest-entry-for
  [example]
  (let [{:keys [status reason]} (classify-example example)]
    [(:id example)
     {:status status
      :reason reason
      :source (str (:file example) ":" (:start-line example))
      :preview (code-preview (:code example))}]))

(defn- manifest-entry-with-existing
  [existing-examples example]
  (let [[id generated] (manifest-entry-for example)
        existing (get existing-examples id)]
    [id (assoc (merge generated existing)
               :source (:source generated)
               :preview (:preview generated))]))

(defn- generated-manifest
  [examples existing-manifest]
  (cond-> {:version 1
           :statuses valid-statuses}
    (seq (:fixtures existing-manifest))
    (assoc :fixtures (:fixtures existing-manifest))

    true
    (assoc :generated-by "datalevin.docs.example-verify/write-skeleton!")

    true
    (assoc :examples (into (sorted-map)
                           (map #(manifest-entry-with-existing
                                   (:examples existing-manifest)
                                   %))
                           examples))))

(defn- write-skeleton!
  [manifest-path examples]
  (let [manifest (generated-manifest examples (read-manifest manifest-path))]
    (spit manifest-path
          (with-out-str
            (binding [*print-namespace-maps* false
                      *print-length* nil
                      *print-level* nil]
              (pprint/pprint manifest)))))
  (println "Wrote example manifest skeleton:" manifest-path))

(defn verify
  "Inventory and run manifest-marked Clojure documentation examples.

  Options:
  :docs-dir              defaults to resources/docs
  :manifest              defaults to test/doc_example_manifest.edn
  :mode                  :verify, :inventory, or :write-skeleton
  :fail-on-unclassified  throw when any Clojure fence lacks a manifest entry"
  [{:keys [docs-dir manifest mode fail-on-unclassified]
    :or {docs-dir "resources/docs"
         manifest "test/doc_example_manifest.edn"
         mode :verify
         fail-on-unclassified false}}]
  (let [raw-examples (extract-examples {:docs-dir docs-dir})]
    (if (= :write-skeleton mode)
      (do
        (write-skeleton! manifest raw-examples)
        {:written manifest
         :count (count raw-examples)})
      (let [manifest-data (read-manifest manifest)
            examples (attach-manifest manifest-data raw-examples)
            unknown (unknown-statuses examples)
            unclassified (filter #(= :unclassified (:status %)) examples)]
        (print-inventory examples)
        (when (seq unknown)
          (throw (ex-info "Unknown example manifest status"
                          {:statuses unknown})))
        (when (and fail-on-unclassified (seq unclassified))
          (throw (ex-info "Unclassified Clojure documentation examples remain"
                          {:count (count unclassified)
                           :examples (mapv #(select-keys % [:id :file :start-line])
                                           unclassified)})))
        (if (= :inventory mode)
          {:status-counts (status-counts examples)}
          (let [results (run-runnable! examples)
                failures (failure-summary results)]
            (when (seq failures)
              (println "Runnable example failures:")
              (pprint/pprint failures)
              (throw (ex-info "Runnable Clojure documentation examples failed"
                              {:failures failures})))
            {:status-counts (status-counts examples)
             :run-count (count results)
             :fail-count (count failures)}))))))
