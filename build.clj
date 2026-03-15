(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/datalevin-docs-standalone.jar")
(def basis (delay (b/create-basis {:project "deps.edn"
                                   :aliases [:prod]})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :class-dir class-dir
                  :ns-compile '[datalevin.docs.core]})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'datalevin.docs.core})
  (println "Built:" uber-file))

(comment
  ;; Build a runnable uberjar.
  ;; clojure -T:build uber
  )
