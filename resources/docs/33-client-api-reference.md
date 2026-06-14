---
title: "Appendix F: datalevin.client API Reference"
chapter: 33
part: "VII — Appendices"
---

# Appendix F: datalevin.client API Reference

The `datalevin.client` namespace is Datalevin's public administrative client
API for a remote Datalevin server. Use it when you need to manage server-side
databases, users, roles, permissions, read replicas, HA membership, or connected
clients.

This appendix is a reference. Chapter 22 explains the operational model: server
mode, RBAC, replicas, HA, monitoring, and deployment choices.

Most application reads and writes should still use ordinary connections from
`datalevin.core/get-conn` or `datalevin.core/create-conn`. Use
`datalevin.client` for server administration and cluster operations.

```clojure
(require '[datalevin.client :as cl])

(def client
  (cl/new-client "dtlv://admin:secret@db-host:8898"))
```

The URI identifies the server and credentials. Database names are passed as
separate arguments to the administrative functions.

---

## 1. Client Lifecycle

| Function | Shape | Use |
| :--- | :--- | :--- |
| `new-client` | `(cl/new-client uri)`, `(cl/new-client uri opts)` | Create a pooled administrative client for a Datalevin server. |
| `disconnect` | `(cl/disconnect client)` | Close the client's pooled network connections. |
| `disconnected?` | `(cl/disconnected? client)` | Return true when the client pool is closed. |

`new-client` performs authentication and maintains a connection pool. Creating a
client includes secure password hashing work, so long-running tools should reuse
one client instead of constructing a new one for every request.

Common options:

| Option | Default | Meaning |
| :--- | :--- | :--- |
| `:pool-size` | `3` | Number of pooled connections held by the client. |
| `:time-out` | `60000` | Milliseconds to wait for an open connection or request before throwing. |
| `:ha-write-retry-timeout-ms` | Derived from HA timing and capped by `:time-out` | Extra retry budget for retryable HA write failover. |
| `:ha-write-retry-delay-ms` | `100` | Sleep between HA write retry rounds. |

Credentials in the URI should be URL encoded when they contain special
characters.

```clojure
(def client
  (cl/new-client
    "dtlv://admin:secret@db-host:8898"
    {:pool-size 8
     :time-out 120000
     :ha-write-retry-timeout-ms 8000
     :ha-write-retry-delay-ms 150}))

(try
  (cl/list-databases client)
  (finally
    (cl/disconnect client)))
```

---

## 2. Database Administration

| Function | Shape | Use |
| :--- | :--- | :--- |
| `create-database` | `(cl/create-database client db-name db-type)` | Create a server-side database. `db-type` is `:datalog` or `:key-value`. |
| `open-database` | `(cl/open-database client db-name db-type)`, plus schema/opts arities | Open a database on the server. `db-type` is `"datalog"`, `"kv"`, or `"engine"`. |
| `close-database` | `(cl/close-database client db-name)` | Force close a server-side database and disconnect clients using it. |
| `drop-database` | `(cl/drop-database client db-name)` | Delete a database. Close it first if it is in use. |
| `list-databases` | `(cl/list-databases client)` | List server-side databases. |
| `list-databases-in-use` | `(cl/list-databases-in-use client)` | List databases currently open on the server. |

Database names supplied to `create-database` are normalized to kebab case by the
server. Prefer stable, simple names and treat them as administrative identifiers,
not user-facing display names.

Most application code does not call `open-database` directly. A remote
`get-conn` or `create-conn` call opens the target database as needed. Use
`open-database` from administrative code when you deliberately want to open or
activate a server-side database without creating an application connection.

```clojure
(cl/create-database client "app" :datalog)

(cl/open-database
  client
  "app"
  "datalog"
  {:user/email {:db/valueType :db.type/string
                :db/unique    :db.unique/identity}}
  {:wal? true})

(cl/list-databases client)
```

`close-database` is operationally stronger than closing one client connection:
it asks the server to close the database and disconnect clients that are using
it. Use it before maintenance operations such as dropping a database.

---

## 3. Users, Roles, and Permissions

Datalevin Server permissions are role based. Create users, create roles, grant
permissions to roles, and assign roles to users.

### 3.1 Users

| Function | Shape | Use |
| :--- | :--- | :--- |
| `create-user` | `(cl/create-user client username password)` | Create a login user. The username is normalized to kebab case. |
| `reset-password` | `(cl/reset-password client username password)` | Set a new password for a user. |
| `drop-user` | `(cl/drop-user client username)` | Delete a user. |
| `list-users` | `(cl/list-users client)` | List users. |

Passwords are stored as salted hashes on the server. User creation and password
reset intentionally perform expensive password hashing.

### 3.2 Roles

| Function | Shape | Use |
| :--- | :--- | :--- |
| `create-role` | `(cl/create-role client role-key)` | Create a role. `role-key` is a keyword. |
| `drop-role` | `(cl/drop-role client role-key)` | Delete a role. |
| `list-roles` | `(cl/list-roles client)` | List roles. |
| `assign-role` | `(cl/assign-role client role-key username)` | Assign a role to a user. |
| `withdraw-role` | `(cl/withdraw-role client role-key username)` | Remove a role from a user. |
| `list-user-roles` | `(cl/list-user-roles client username)` | List roles assigned to a user. |

### 3.3 Permissions

| Function | Shape | Use |
| :--- | :--- | :--- |
| `grant-permission` | `(cl/grant-permission client role-key action object target)` | Grant a permission to a role. |
| `revoke-permission` | `(cl/revoke-permission client role-key action object target)` | Revoke a permission from a role. |
| `list-role-permissions` | `(cl/list-role-permissions client role-key)` | List permissions granted to a role. |
| `list-user-permissions` | `(cl/list-user-permissions client username)` | List effective permissions for a user through assigned roles. |

Permission actions:

| Action | Meaning |
| :--- | :--- |
| `:datalevin.server/view` | Read/view access. |
| `:datalevin.server/alter` | Includes view and allows modifying existing resources. |
| `:datalevin.server/create` | Includes lower actions and allows creation. |
| `:datalevin.server/control` | Administrative control. |

Permission objects:

| Object | Target examples |
| :--- | :--- |
| `:datalevin.server/database` | Database name, or `nil` for all databases. |
| `:datalevin.server/user` | Username, or `nil` for all users. |
| `:datalevin.server/role` | Role keyword, or `nil` for all roles. |
| `:datalevin.server/server` | Usually `nil`; subsumes server-level control. |

Example: read-only access to one database:

```clojure
(cl/create-user client "alice" "initial-password")
(cl/create-role client :app.role/reader)

(cl/grant-permission
  client
  :app.role/reader
  :datalevin.server/view
  :datalevin.server/database
  "app")

(cl/assign-role client :app.role/reader "alice")
```

Example: administrative server control:

```clojure
(cl/create-role client :app.role/admin)

(cl/grant-permission
  client
  :app.role/admin
  :datalevin.server/control
  :datalevin.server/server
  nil)
```

Use the narrowest permission that supports the task. Application users usually
need database-scoped permissions, not server control.

---

## 4. Replication and HA

| Function | Shape | Use |
| :--- | :--- | :--- |
| `replica-status` | `(cl/replica-status client db-name)` | Return async read-replica status for an open database. |
| `ha-update-membership!` | `(cl/ha-update-membership! client db-name spec)` | Commit an operator-driven consensus HA membership update. |

`replica-status` is for async read replicas. The returned map includes fields
such as `:replica-applied-lsn`, `:replica-source-durable-lsn`,
`:replica-source-committed-lsn`, `:replica-lag-lsn`,
`:replica-degraded-reason`, and `:replica-last-error`.

```clojure
(cl/replica-status client "app")
```

`ha-update-membership!` changes the authoritative membership for an open HA
database. Chapter 22 explains the HA model, fencing, voters, leases, and the
operational checks around this function.

Useful `spec` keys:

| Key | Meaning |
| :--- | :--- |
| `:ha-members` | Replacement data-node member list. |
| `:ha-control-plane {:voters [...]}` | Replacement control-plane voter config. |
| `:ha-control-plane-voters` | Alternative shape for replacement voters. |
| `:expected-membership-hash` | Optional compare-and-set guard. |
| `:clear-leases?` | Defaults to `true`; forces leadership to be reacquired. |
| `:replace-voters?` | Defaults to `true`; updates the Raft voter set. |
| `:timeout-ms` | Optional control-plane operation timeout. |

```clojure
(cl/ha-update-membership!
  client
  "app"
  {:ha-members
   [{:node-id 1 :endpoint "node-a:8898"}
    {:node-id 2 :endpoint "node-b:8898"}
    {:node-id 3 :endpoint "node-c:8898"}]
   :ha-control-plane
   {:voters [{:peer-id "node-a:9098" :promotable? true :ha-node-id 1}
             {:peer-id "node-b:9098" :promotable? true :ha-node-id 2}
             {:peer-id "node-c:9098" :promotable? true :ha-node-id 3}]}})
```

Membership updates are operator actions. Automating them without health checks,
clock discipline, and rollback procedures can turn failover into a data-safety
problem.

---

## 5. System Database and Connected Clients

| Function | Shape | Use |
| :--- | :--- | :--- |
| `query-system` | `(cl/query-system client query & args)` | Run a Datalog query against the server system database. Do not pass `$`; the server supplies it. |
| `show-clients` | `(cl/show-clients client)` | Return information about currently connected clients. |
| `disconnect-client` | `(cl/disconnect-client client client-id)` | Force disconnect a connected client by UUID. |

`query-system` is for administrative inspection. The query is a normal Datalog
query, but unlike `d/q`, arguments do not include a DB value:

```clojure
(cl/query-system
  client
  '[:find ?user
    :where [?u :user/name ?user]])
```

Use `show-clients` before `disconnect-client`:

```clojure
(def clients (cl/show-clients client))

;; client-id must be a java.util.UUID value returned by show-clients.
(cl/disconnect-client client some-client-id)
```

Do not confuse `disconnect-client` with `disconnect`. `disconnect-client`
disconnects another server-side client session. `disconnect` closes the
administrative client object in your process.

---

## 6. Operational Notes

- Keep one administrative client per operation scope and reuse it.
- Use server URIs such as `dtlv://admin:secret@host:8898`; pass database names
  separately.
- URL encode usernames and passwords in URIs when they contain special
  characters.
- Prefer least-privilege roles for applications and reserve server control for
  operators.
- Treat `close-database`, `drop-database`, `ha-update-membership!`, and
  `disconnect-client` as operational actions that should be logged.
- Use Chapter 22 for deployment guidance and this appendix for function lookup.

## Summary

`datalevin.client` is the public API for administering a Datalevin server. It is
not the normal query or transaction surface for application data; that remains
the connection API covered in the main chapters. Use the client namespace when
the unit of work is the server, a database lifecycle operation, RBAC, replica
status, HA membership, or connected-client management.
