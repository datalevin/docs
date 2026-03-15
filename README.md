# Datalevin Documentation Site

## Prerequisites

- Java 21+
- [Clojure CLI](https://clojure.org/guides/install_clojure) 1.12+
- Node.js / npm (for Tailwind CSS)

## Setup

```bash
# Clone with submodules
git clone --recurse-submodules <repo-url>

# Or if already cloned, init submodules
git submodule update --init --recursive

# Install npm dependencies (Tailwind CSS)
npm install

# Create .env from example
cp .env.example .env
```

Edit `.env` as needed. The defaults work for local development; GitHub OAuth credentials are optional.

## Development

Start the nREPL:

```bash
clojure -M:dev
```

Then in the REPL:

```clojure
(require 'dev)
(dev/start)
```

This starts:
- **Web server** on http://localhost:3000
- **Tailwind CSS** watcher (recompiles on class changes)
- **Live reload** via file watcher — editing `.clj`, `.css`, or `.md` files triggers an automatic browser refresh

Other REPL commands:

```clojure
(dev/stop)      ; stop everything
(dev/restart)   ; full restart
(dev/reload!)   ; manually trigger a browser refresh
```

## Build CSS (production)

```bash
npm run css:build
```

## Build uberjar

Build a standalone jar for small VMs:

```bash
npm run css:build
clojure -T:build uber
```

This produces:

```bash
target/datalevin-docs-standalone.jar
```

The uberjar avoids resolving the production classpath on every boot and is the preferred deployment target on a 1 GB VM.

## Production

Start the docs site with explicit JVM heap limits:

```bash
scripts/start-prod.sh
```

Set `ENV=prod` and a real `SESSION_SECRET` before starting. Startup now fails fast if `ENV=prod` and `SESSION_SECRET` is missing.
In production, startup also fails fast unless outbound email is configured with `MAIL_FROM` and `SMTP_HOST`.

That wrapper runs:

```bash
clojure -J-Xms256m -J-Xmx384m -J-XX:+UseG1GC -J-XX:MaxGCPauseMillis=200 -J-XX:+UseStringDeduplication -J-XX:MaxMetaspaceSize=128m -M:prod -m datalevin.docs.core
```

On small VMs, setting `-Xms` and a conservative `-Xmx` avoids the JVM expanding until the host OOM killer intervenes while still leaving more room for Datalevin's mmap usage. Provision at least `1 GB` of swap on these hosts so short bursts of heap or page-cache pressure do not immediately turn into an OOM kill. The prod wrapper also defaults to G1 with string deduplication and a 128 MB metaspace cap. You can override the defaults if needed:

```bash
JAVA_XMS=256m JAVA_XMX=768m scripts/start-prod.sh
```

If you want a lower-overhead collector on a very small VM, switch the wrapper to Serial GC:

```bash
JAVA_GC=serial scripts/start-prod.sh
```

For deployed hosts, prefer the uberjar instead of invoking Clojure directly:

```bash
ENV=prod SESSION_SECRET=replace-me \
	java -Xms256m -Xmx384m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+UseStringDeduplication -XX:MaxMetaspaceSize=128m -jar target/datalevin-docs-standalone.jar
```

## systemd

An example unit file is included at `deploy/systemd/datalevin-docs.service`.
It sets `MemoryHigh=800M`, `MemoryMax=900M`, and `OOMPolicy=stop` so the service is constrained before it can pressure unrelated processes on the same VM.

Typical install steps:

```bash
sudo install -d /opt/datalevin-docs /etc/datalevin-docs
sudo install -m 0644 target/datalevin-docs-standalone.jar /opt/datalevin-docs/datalevin-docs-standalone.jar
sudo install -m 0644 deploy/systemd/datalevin-docs.service /etc/systemd/system/datalevin-docs.service
```

Create `/etc/datalevin-docs/datalevin-docs.env` with at least:

```bash
ENV=prod
PORT=3000
BASE_URL=https://docs.example.com
DB_PATH=/var/lib/datalevin-docs/data
SESSION_SECRET=replace-with-a-real-secret
MAIL_FROM="Datalevin Docs <noreply@example.com>"
SMTP_HOST=smtp.example.com
SMTP_PORT=587
SMTP_USER=smtp-user
SMTP_PASS=smtp-password
SMTP_TLS=true
```

Without SMTP config, development keeps logging verification and reset links to the console instead of sending email.

Then enable the service:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now datalevin-docs
```
