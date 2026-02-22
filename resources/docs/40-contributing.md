---
title: "Contributing to Datalevin and the Ecosystem"
chapter: 40
part: "VIII — Internals and Extensions"
---

# Chapter 40: Contributing to Datalevin and the Ecosystem

Datalevin is more than just a piece of software; it is a community-driven project aiming to redefine what a modern database can be. Whether you are a seasoned database engineer or a developer who just started using Datalog, there are many ways you can help grow the ecosystem.

This final chapter outlines how you can contribute to Datalevin and where to find the community.

---

## 1. The Datalevin Philosophy

Datalevin is built on three core values:
1.  **Performance**: If it isn't fast, it isn't Datalevin. We value zero-copy, memory-mapped efficiency.
2.  **Simplicity**: We prefer small, focused components over monolithic frameworks.
3.  **Versatility**: We believe one engine can—and should—handle Logic, Graph, KV, and Search.

If you share these values, you'll feel right at home in the community.

---

## 2. How to Contribute

### 2.1 Code and Core Improvements
The Datalevin source code is hosted on GitHub. We welcome pull requests for:
- Performance optimizations in the query engine or storage layer.
- Bug fixes and improved error messages.
- New built-in Datalog predicates.

### 2.2 Documentation and Education
A database is only as good as its documentation. You can contribute by:
- Improving the clarity of these chapters.
- Submitting new code examples to the website.
- Writing blog posts about how you use Datalevin in your projects.

### 2.3 The Ecosystem
Building tools *around* Datalevin is just as important as building the database itself.
- **`biff-datalevin`**: Help integrate Datalevin into the Biff web framework.
- **Language Bindings**: While Datalevin is JVM-first, we are always interested in making it accessible to other languages via the Server protocol or specialized pods.

---

## 3. Finding the Community

- **GitHub**: [juji-io/datalevin](https://github.com/juji-io/datalevin) is the primary hub for development.
- **Clojurians Slack/Discord**: Join the `#datalevin` channel to chat with the maintainers and other users.
- **Issue Tracker**: Use GitHub Issues to report bugs or request new features.

---

## 4. A Final Word

Datalevin started with a simple goal: to create a Datalog database that was as fast as it was flexible. Today, it has grown into a powerful substrate for everything from traditional web apps to the next generation of intelligent agents.

Thank you for being part of this journey. We can't wait to see what you build with Datalevin.
