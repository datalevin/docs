---
title: "Getting Started: Embedded, Server, and Babashka Pod Modes"
chapter: 2
part: "I — Foundations: A Multi-Paradigm Database"
---

# Chapter 2: Getting Started: Embedded, Server, and Babashka Pod Modes

Getting started with Datalevin is designed to be frictionless. Whether you want to embed a high-performance engine in your Clojure app or run a standalone server, you can be up and running in minutes.

This chapter provides a quick-start guide for the three primary ways to use Datalevin.

---

## 1. Quick Start: Embedded Mode

Embedded mode is the most common way to use Datalevin in the Clojure ecosystem.

### 1.1 Add the Dependency
Add the following to your `deps.edn` file:

```clojure
{datalevin/datalevin {:mvn/version "0.9.22"}}
```

### 1.2 Your First Query
Open a REPL and run the following code to create a database, transact data, and query it.

> **Note**: For new Datalog databases, **WAL (Write-Ahead Log) mode** is enabled by default. This provides high write performance while maintaining strong durability (Chapter 4).

```clojure
(require '[datalevin.core :as d])
...
;; 1. Create a connection (stores data in /tmp/mydb)
(def conn (d/get-conn "/tmp/mydb" {}))

;; 2. Transact some data
(d/transact! conn [{:user/name "Alice" :user/age 30}
                   {:user/name "Bob"   :user/age 25}])

;; 3. Run a Datalog query
(d/q '[:find ?name
       :where [?e :user/name ?name]
              [?e :user/age ?age]
              [(> ?age 28)]]
     (d/db conn))
;; => #{["Alice"]}

;; 4. Close the connection
(d/close-conn conn)
```

---

## 2. Running as a Standalone Server

If you need a centralized database for multiple services, you can run Datalevin as a standalone server.

### 2.1 Download and Start
1.  Download the latest server binary from the [GitHub Releases](https://github.com/juji-io/datalevin/releases) page.
2.  Start the server:
    ```bash
    ./datalevin-server -p 8898 -d /path/to/data
    ```

### 2.2 Using the CLI
Datalevin comes with a built-in interactive shell. Connect to your running server:
```bash
./dtlv -u datalevin -p <your-password> -h localhost -P 8898
```
From the shell, you can create databases, run queries, and manage users via the RBAC system (Chapter 28).

---

## 3. Scripting with Babashka Pods

For quick scripts or CLI tools, you can use the Datalevin **Babashka Pod**. This gives you full Datalog power without the JVM startup delay.

### 3.1 Loading the Pod
In your `script.clj`:

```clojure
(require '[babashka.pods :as pods])
(pods/load-pod 'org.babashka/datalevin "0.9.22")
(require '[pod.babashka.datalevin :as d])

(let [conn (d/get-conn "/tmp/bb-db")]
  (d/transact! conn [{:msg "Hello from Babashka"}])
  (println (d/q '[:find ?m :where [_ :msg ?m]] (d/db conn))))
```

Run it instantly with `bb script.clj`.

---

## 4. Next Steps

Now that you have Datalevin running, it's time to understand the underlying principles that make it unique.

- **Chapter 3**: Understand the **Mental Model** of facts and datoms.
- **Chapter 4**: Learn about the **Storage Fundamentals** and why zero-copy matters.
- **Chapter 5**: Dive into **Attributes and Namespaces**.

Welcome to the world of logical and intelligent databases!
