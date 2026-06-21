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

## Multi-Language Code Examples

For Datalevin API examples that apply across supported host languages, wrap the
parallel code fences in a `multi-lang` block and keep this order: Clojure, Java,
Python, JavaScript.

````markdown
<div class="multi-lang">

```clojure
(d/q '[:find ?e :where [?e :user/name "Alice"]] db)
```

```java
Datalevin.q("[:find ?e :where [?e :user/name \"Alice\"]]", db);
```

```python
d.q('[:find ?e :where [?e :user/name "Alice"]]', db)
```

```javascript
d.q(`[:find ?e :where [?e :user/name "Alice"]]`, db);
```

</div>
````

Use ordinary single-language fences for shell commands, SQL, EDN/Datalog
reference snippets, Babashka pods, server REPL tasks, and Clojure-specific APIs.

## Pre-Publication Example Verification

The docs include a manifest-driven verifier for Clojure examples:

```bash
# Inventory Clojure fences and current manifest status counts
clojure -X:verify-examples :mode :inventory

# Add/update manifest skeleton entries after large doc edits
clojure -X:verify-examples :mode :write-skeleton

# Run examples marked :runnable in test/doc_example_manifest.edn
clojure -X:verify-examples

# Release-gate mode: fail if any Clojure fence is still unclassified
clojure -X:verify-examples :fail-on-unclassified true
```

Classify each executable-language code fence in `test/doc_example_manifest.edn`
as one of:

- `runnable`: self-contained or runnable with a small chapter fixture
- `fragment`: illustrative code that depends on omitted surrounding setup
- `external`: requires a server, network service, credentials, native model, or
  destructive/operator action
- `api-sketch`: intentionally not a tested public API example

The release-blocking verification pass should mark and run every runnable
Clojure example, then run a representative Java/Python/JavaScript matrix for
each API family covered by multi-language examples. Do not claim in the
manuscript that examples were executed until the manifest records the commands,
Datalevin version, host runtimes, and pass/fail results.

Skeleton generation preserves existing classifications for current snippet IDs,
updates source/preview metadata, adds new snippets, and drops entries for
removed snippets.

Manifest entries may use `:setup` and `:teardown` strings for local fixtures.
For repeated fixtures, define a top-level `:fixtures` map and reference entries
with `:fixture`.

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

## Build final PDF

The final PDF is generated from the Markdown chapters with a print-oriented
preprocessing step. The generated PDF uses a page-numbered table of contents,
adds an index from `resources/docs/index-terms.edn` and generated function
entries, and keeps Clojure fenced code blocks only; Java, Python, JavaScript,
shell, JSON, SQL, and other non-Clojure fenced blocks remain in the web book but
are omitted from the print PDF source.

Prerequisites:

- `pandoc`
- XeLaTeX, usually from TeX Live
- `makeindex`, usually included with TeX Live
- `rsvg-convert` for SVG diagrams
- The PDF type stack configured in `build.clj`: Charter for body text, Avenir
  Next for headings, and Menlo for code. These are available on macOS; adjust
  the font metadata in `build.clj` if building on another platform.

Build it with:

```bash
clojure -T:build final-pdf
```

This produces:

```bash
target/pdf/datalevin-definitive-guide.md
target/pdf/datalevin-definitive-guide.tex
target/pdf/datalevin-definitive-guide.pdf
```

Set `DATALEVIN_VERSION` to override the default version used for
`{{datalevin-version}}` substitution.

## Production

Start the docs site from the built uberjar with explicit JVM heap limits:

```bash
clojure -T:build uber
scripts/start-prod.sh
```

Set `ENV=prod`, a real `SESSION_SECRET`, and `DATALEVIN_VERSION` before
starting. Startup fails fast if `ENV=prod` and either `SESSION_SECRET` or
`DATALEVIN_VERSION` is missing. In production, startup also fails fast unless
outbound email is configured with `MAIL_FROM` and `SMTP_HOST`.

That wrapper runs the same jar-based startup pattern as the `systemd` unit:

```bash
java -Xms256m -Xmx384m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+UseStringDeduplication -XX:MaxMetaspaceSize=128m -jar target/datalevin-docs-standalone.jar
```

On small VMs, setting `-Xms` and a conservative `-Xmx` avoids the JVM expanding until the host OOM killer intervenes while still leaving more room for Datalevin's mmap usage. Provision at least `1 GB` of swap on these hosts so short bursts of heap or page-cache pressure do not immediately turn into an OOM kill. The prod wrapper also defaults to G1 with string deduplication and a 128 MB metaspace cap. You can override the defaults if needed:

```bash
JAVA_XMS=256m JAVA_XMX=768m scripts/start-prod.sh
```

If you want a lower-overhead collector on a very small VM, switch the wrapper to Serial GC:

```bash
JAVA_GC=serial scripts/start-prod.sh
```

Override `APP_JAR` if the jar lives outside the repo checkout:

```bash
APP_JAR=/opt/datalevin-docs/datalevin-docs-standalone.jar scripts/start-prod.sh
```

## systemd

An example unit file is included at `deploy/systemd/datalevin-docs.service`.
It keeps `MemoryHigh=800M` and `OOMPolicy=stop`, while relying on the JVM heap limits and Datalevin's own mapsize instead of a hard `MemoryMax` cap.

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
