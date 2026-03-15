(ns datalevin.docs.seed
  "Seed test data for development"
  (:require [datalevin.core :as d]
            [datalevin.docs.config :as config]
            [datalevin.docs.schema :as schema]
            [biff.datalevin.auth :as auth])
  (:import [java.util Date UUID]))

(def db-path (:db-path (config/resolve-env {})))

(defn- example-tx
  [author-id doc-section code]
  {:example/id (UUID/randomUUID)
   :example/code code
   :example/author [:user/id author-id]
   :example/doc-section doc-section
   :example/removed? false
   :example/created-at (Date.)})

(defn seed []
  (println "Seeding database...")
  
  (let [conn (d/get-conn db-path schema/schema)
        db (d/db conn)
        
        test-user-id (UUID/randomUUID)
        test-user-tx {:user/id test-user-id
                      :user/email "test@example.com"
                      :user/username "testuser"
                      :user/password-hash (auth/hash-password "password123")
                      :user/role :user
                      :user/email-verified? true
                      :user/created-at (Date.)}
        
        demo-user-id (UUID/randomUUID)
        demo-user-tx {:user/id demo-user-id
                      :user/email "demo@example.com"
                      :user/username "demouser"
                      :user/password-hash (auth/hash-password "password123")
                      :user/role :user
                      :user/email-verified? true
                      :user/created-at (Date.)}
        
        admin-user-id (UUID/randomUUID)
        admin-user-tx {:user/id admin-user-id
                       :user/email "admin@example.com"
                       :user/username "admin"
                       :user/password-hash (auth/hash-password "admin123")
                       :user/role :admin
                       :user/email-verified? true
                       :user/created-at (Date.)}]
    
    (d/transact! conn [test-user-tx demo-user-tx admin-user-tx])
    (println "Created test users:")
    (println "  - test@example.com / password123 (role: user)")
    (println "  - demo@example.com / password123 (role: user)")
    (println "  - admin@example.com / admin123 (role: admin)")
    
    (let [examples [(example-tx test-user-id
                                "09-datalog-fundamentals"
                                "(d/q '[:find ?e\n  :where [?e :user/name]]\n  conn)")

                    (example-tx demo-user-id
                                "07-transactions"
                                "(d/transact! conn [{:user/email \"alice@example.com\"\n                         :user/name \"Alice\"\n                         :user/age 30}])")

                    (example-tx admin-user-id
                                "06-key-value-store"
                                "(d/transact! conn [[:put {:db/ident :kv\n                    :key \"mykey\"\n                    :value \"myvalue\"}]])\n\n(d/get-value conn :kv \"mykey\")")

                    (example-tx test-user-id
                                "17-full-text-search"
                                "(d/q '[:find ?e\n  :in $ ?search\n  :where [?e :doc/content ?content]\n  [(fulltext $ :doc/content ?search) [[?e ?match]]]]\n  conn \"database\")")

                    (example-tx demo-user-id
                                "18-vector-search"
                                "(d/q '[:find ?e ?score\n  :in $ ?query-vector [?limit]\n  :where [?e :embedding/vector ?vec]\n  [(.score (datalevin.search/similarity-search ?vec ?query-vector)) ?score]]\n  db query-vec 10)")]]
      
      (d/transact! conn examples)
      (println "Created" (count examples) "example submissions"))
    
    (d/close conn)
    (println "\nSeed complete!")))

(defn -main [& args]
  (seed))
