(ns datalevin.docs.seed
  "Seed test data for development"
  (:require [datalevin.core :as d]
            [datalevin.docs.schema :as schema]
            [biff.datalevin.auth :as auth]
            [biff.datalevin.session :as session])
  (:import [java.util Date UUID]))

(def db-path "data/app-db")

(defn seed []
  (println "Seeding database...")
  
  (let [conn (d/get-conn db-path schema/user-schema)
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
    
    (let [examples [{:example/id (UUID/randomUUID)
                     :example/title "Basic Datalog Query"
                     :example/description "Simple query to find all users"
                     :example/code "(d/q '[:find ?e\n  :where [?e :user/name]]\n  conn)"
                     :example/output "#{[:user/name \"Alice\"] [:user/name \"Bob\"]}"
                     :example/author [:user/id test-user-id]
                     :example/category :example/datalog
                     :example/doc-section "09-datalog-fundamentals"
                     :example/created-at (Date.)}
                    
                    {:example/id (UUID/randomUUID)
                     :example/title "Transaction with Schema"
                     :example/description "Transacting data with schema types"
                     :example/code "(d/transact! conn [{:user/email \"alice@example.com\"\n                         :user/name \"Alice\"\n                         :user/age 30}])"
                     :example/output "{:db-before ..., :db-after ..., :tx-data [...]}"
                     :example/author [:user/id demo-user-id]
                     :example/category :example/datalog
                     :example/doc-section "07-transactions"
                     :example/created-at (Date.)}
                    
                    {:example/id (UUID/randomUUID)
                     :example/title "Key-Value Put/Get"
                     :example/description "Basic key-value operations"
                     :example/code "(d/transact! conn [[:put {:db/ident :kv\n                    :key \"mykey\"\n                    :value \"myvalue\"}]])\n\n(d/get-value conn :kv \"mykey\")"
                     :example/output "\"myvalue\""
                     :example/author [:user/id admin-user-id]
                     :example/category :example/kv
                     :example/doc-section "06-key-value-store"
                     :example/created-at (Date.)}
                    
                    {:example/id (UUID/randomUUID)
                     :example/title "Full-Text Search"
                     :example/description "Searching with tokenized strings"
                     :example/code "(d/q '[:find ?e\n  :in $ ?search\n  :where [?e :doc/content ?content]\n  [(fulltext $ :doc/content ?search) [[?e ?match]]]]\n  conn \"database\")"
                     :example/output "#{[... :doc/content \"...database...\"]}"
                     :example/author [:user/id test-user-id]
                     :example/category :example/search
                     :example/doc-section "17-full-text-search"
                     :example/created-at (Date.)}
                    
                    {:example/id (UUID/randomUUID)
                     :example/title "Vector Similarity Search"
                     :example/description "Find similar embeddings"
                     :example/code "(d/q '[:find ?e ?score\n  :in $ ?query-vector [?limit]\n  :where [?e :embedding/vector ?vec]\n  [(.score (datalevin.search/similarity-search ?vec ?query-vector)) ?score]]\n  db query-vec 10)"
                     :example/output "#{[entity-id 0.95] [entity-id 0.87]}"
                     :example/author [:user/id demo-user-id]
                     :example/category :example/vector
                     :example/doc-section "18-vector-search"
                     :example/created-at (Date.)}]]
      
      (d/transact! conn examples)
      (println "Created" (count examples) "example submissions"))
    
    (d/close conn)
    (println "\nSeed complete!")))

(defn -main [& args]
  (seed))
