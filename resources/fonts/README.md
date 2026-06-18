# Bundled figure fonts

These fonts are bundled so the PDF build renders the diagrams
(`resources/public/images/diagrams/*.svg`) with their intended typefaces on any
machine, instead of depending on whatever sans/mono each host happens to have
installed.

- **Inter** — the figures' sans-serif (titles, labels, captions).
- **IBM Plex Mono** — the figures' monospace (code, identifiers, values).

Both are licensed under the SIL Open Font License 1.1 (see `Inter-OFL.txt` and
`IBMPlexMono-OFL.txt`), which permits bundling and redistribution. The files here
were taken from a TeX Live distribution.

## How the build uses them

`build.clj`'s `convert-svg-assets!` converts each SVG to a vector PDF with
`rsvg-convert -f pdf`, run with:

- `FONTCONFIG_FILE` pointing at a generated `target/pdf/fonts.conf` that adds
  this directory on top of the system fonts, and
- `PANGOCAIRO_BACKEND=fc`, which forces pango's fontconfig/FreeType backend.

The `PANGOCAIRO_BACKEND=fc` part matters on macOS: by default pango there uses
CoreText, which ignores fontconfig and falls back to system fonts (Menlo, the UI
font), producing the wrong typefaces in the PDF even though the browser renders
the SVGs correctly.

The SVG `font-family` stacks list `Inter` and `"IBM Plex Mono"` first, so these
faces are used when available and the diagrams still degrade to system
sans/mono in a browser without them.
