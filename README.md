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

That wrapper runs:

```bash
clojure -J-Xms256m -J-Xmx512m -M:prod -m datalevin.docs.core
```

On small VMs, setting `-Xms` and `-Xmx` avoids the JVM expanding until the host OOM killer intervenes. You can override the defaults if needed:

```bash
JAVA_XMS=256m JAVA_XMX=768m scripts/start-prod.sh
```

For deployed hosts, prefer the uberjar instead of invoking Clojure directly:

```bash
ENV=prod SESSION_SECRET=replace-me \
java -Xms256m -Xmx512m -jar target/datalevin-docs-standalone.jar
```

## systemd

An example unit file is included at `deploy/systemd/datalevin-docs.service`.

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
```

Then enable the service:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now datalevin-docs
```
