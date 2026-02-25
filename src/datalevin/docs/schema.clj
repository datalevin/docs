(ns datalevin.docs.schema
  "Application schema for datalevin-docs"
  (:require [datalevin.core :as d]))

(def schema
  {;; Users
   :user/id {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :user/email {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :user/username {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :user/password-hash {:db/valueType :db.type/string}
   :user/github-id {:db/valueType :db.type/long :db/unique :db.unique/identity}
   :user/github-username {:db/valueType :db.type/string}
   :user/avatar-url {:db/valueType :db.type/string}
   :user/role {:db/valueType :db.type/keyword}
   :user/email-verified? {:db/valueType :db.type/boolean}
   :user/created-at {:db/valueType :db.type/instant}

   ;; Sessions
   :session/id {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :session/user {:db/valueType :db.type/ref}
   :session/expires-at {:db/valueType :db.type/instant}

   ;; Email verification tokens
   :verification-token/token {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :verification-token/user {:db/valueType :db.type/ref}
   :verification-token/expires-at {:db/valueType :db.type/instant}

   ;; Password reset tokens
   :password-reset/token {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :password-reset/user {:db/valueType :db.type/ref}
   :password-reset/expires-at {:db/valueType :db.type/instant}

   ;; Documentation
   :doc/slug {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :doc/title {:db/valueType :db.type/string}
   :doc/chapter {:db/valueType :db.type/long}
   :doc/part {:db/valueType :db.type/string}
   :doc/content {:db/valueType :db.type/string :db/fulltext true}
   :doc/html {:db/valueType :db.type/string}
   :doc/filename {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :doc/order {:db/valueType :db.type/long}
   :doc/updated-at {:db/valueType :db.type/instant}

   ;; Examples
   :example/id {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :example/code {:db/valueType :db.type/string}
   :example/author {:db/valueType :db.type/ref}
   :example/doc-section {:db/valueType :db.type/string}
   :example/removed? {:db/valueType :db.type/boolean}
   :example/created-at {:db/valueType :db.type/instant}

   ;; Book metadata
   :book/title {:db/valueType :db.type/string}
   :book/subtitle {:db/valueType :db.type/string}
   :book/author {:db/valueType :db.type/string}
   :book/version {:db/valueType :db.type/string}
   :book/pdf-generated-at {:db/valueType :db.type/instant}})

(def user-schema
  {:user/id {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :user/email {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :user/username {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :user/password-hash {:db/valueType :db.type/string}
   :user/github-id {:db/valueType :db.type/long :db/unique :db.unique/identity}
   :user/github-username {:db/valueType :db.type/string}
   :user/avatar-url {:db/valueType :db.type/string}
   :user/role {:db/valueType :db.type/keyword}
   :user/email-verified? {:db/valueType :db.type/boolean}
   :user/created-at {:db/valueType :db.type/instant}

   :session/id {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :session/user {:db/valueType :db.type/ref}
   :session/expires-at {:db/valueType :db.type/instant}

   ;; Email verification tokens
   :verification-token/token {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :verification-token/user {:db/valueType :db.type/ref}
   :verification-token/expires-at {:db/valueType :db.type/instant}

   ;; Password reset tokens
   :password-reset/token {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :password-reset/user {:db/valueType :db.type/ref}
   :password-reset/expires-at {:db/valueType :db.type/instant}})

(def example-schema
  {:example/id {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :example/code {:db/valueType :db.type/string :db/fulltext true}
   :example/author {:db/valueType :db.type/ref}
   :example/doc-section {:db/valueType :db.type/string}
   :example/removed? {:db/valueType :db.type/boolean}
   :example/created-at {:db/valueType :db.type/instant}})
