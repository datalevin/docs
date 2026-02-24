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
