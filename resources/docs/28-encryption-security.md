---
title: "Encryption at Rest and Security Models"
chapter: 28
part: "VI — Systems and Operations"
---

# Chapter 28: Encryption at Rest and Security Models

Security is a multi-layered responsibility. While Datalevin provides robust tools for access control, protecting the physical data requires a strategy that spans from the hardware up to the application code.

This chapter provides guidance on how to secure your Datalevin data at rest and how to use the built-in Role-Based Access Control (RBAC) in Datalevin Server.

---

## 1. Encryption at Rest: A Layered Approach

Datalevin does not currently provide database-level encryption (e.g., TDE). Instead, it follows the industry-standard recommendation of securing data at the layers where encryption is most efficient and manageable.

### 1.1 The Security Stack
To protect your data at rest, consider encryption at these levels, from bottom to top:

1.  **Hardware**: Use hardware-encrypted drives or Hardware Security Modules (HSMs) for key management.
2.  **Disk/Volume**: This is the most common and recommended starting point.
    *   **On-Premise**: Use **LUKS** (Linux) or **FileVault** (macOS).
    *   **Cloud**: Use managed encryption like **AWS EBS encryption**, **GCP Persistent Disk encryption**, or **Azure Disk Encryption**. These handles key rotation and management for you with zero performance overhead.
3.  **File System**: Use tools like `fscrypt` to encrypt specific directories at the OS level.
4.  **Application Level**: For extremely sensitive fields (e.g., PII, credit card numbers), encrypt the data in your application code before sending it to Datalevin.

### 1.2 Multi-Tenancy and Envelope Encryption
If you are building a SaaS with multiple tenants, you may need **Envelope Encryption**.
- Use a cloud-managed service like **AWS KMS** or **GCP KMS** to manage per-tenant "Data Encryption Keys" (DEKs).
- Your application encrypts the sensitive fields with a tenant's DEK before transacting them as a blob or string into Datalevin.

---

## 2. Security Models: Datalevin Server RBAC

When running in Server mode (Chapter 2), Datalevin provides a comprehensive **Role-Based Access Control (RBAC)** system to ensure that only authorized users can query or modify specific data.

### 2.1 Users and Roles
- **Users**: Access is secured by a username and a salted/hashed password.
- **Default User**: Every server has a default administrative user named `datalevin`. You should change its default password immediately using the `DATALEVIN_DEFAULT_PASSWORD` environment variable.
- **Roles**: Permissions are granted to roles, which are then assigned to users. Every user also has a built-in private role named `:datalevin.role/<username>`.

### 2.2 The Permission Triple: Act, Obj, Tgt
Permissions in Datalevin are defined by three components:

1.  **Action (`act`)**: What the user can do.
    *   `:datalevin.server/view`: Read-only access (query, pull).
    *   `:datalevin.server/alter`: Modify data (transact).
    *   `:datalevin.server/create`: Create new databases or users.
    *   `:datalevin.server/control`: Full administrative control.
2.  **Object (`obj`)**: What type of thing they are acting on (e.g., `:datalevin.server/database`, `:datalevin.server/user`).
3.  **Target (`tgt`)**: The specific name of the database, user, or role. Use `nil` to target all objects of that type.

### 2.3 Managing Access via REPL
Administrative tasks are performed via the server REPL:

```clojure
;; 1. Create a new user
(create-user "alice" "secure-password")

;; 2. Grant 'view' permission on a specific database to Alice
(grant-permission :datalevin.role/alice 
                  :datalevin.server/view 
                  :datalevin.server/database 
                  "prod-db")

;; 3. Create a custom 'editor' role and assign it to Alice
(create-role :app.role/editor)
(grant-permission :app.role/editor :datalevin.server/alter :datalevin.server/database "prod-db")
(assign-role "alice" :app.role/editor)
```

---

## 3. Summary: Security Best Practices

1.  **Start with Cloud Disk Encryption**: If you are on AWS/GCP/Azure, enable volume encryption for your database storage.
2.  **Change the Default Admin Password**: Never leave the `datalevin` user with its default credentials.
3.  **Principle of Least Privilege**: Create specific users for your applications and grant them only the `:view` or `:alter` permissions they need for specific databases.
4.  **Use Application-Level Encryption for PII**: Don't rely on the database to protect highly sensitive fields; encrypt them before they leave your application server.

By combining infrastructure-level encryption with Datalevin's granular RBAC, you can build systems that are both highly functional and securely defended.
