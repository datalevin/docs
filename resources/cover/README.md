# Cover Sources

KDP print cover files are separate from the interior manuscript PDF. The final
upload cover should be one wraparound PDF containing, from left to right:

```text
back cover | spine | front cover
```

`front-cover.svg` is only the front panel draft. It is sized at the current
7 in by 9.25 in trim. The final KDP cover upload still needs to be a wraparound
PDF with bleed, spine, back cover, and barcode area. The Datalevin logo and
imprint mark are inlined there as vector geometry so the cover can be converted
to PDF without referencing raster assets.

`datalevin-logo.svg` is the standalone vector version of the Datalevin logo,
traced from the project PNG mark.

`wraparound-cover-450-white.svg` is a draft full KDP cover for a projected
450-page paperback on black-and-white white paper. It uses the current 7 in by
9.25 in trim, 0.125 in bleed, and a 1.0134 in spine:

```text
spine = 450 pages * 0.002252 in/page = 1.0134 in
cover size = 15.2634 in by 9.5 in
```

If the final paperback uses cream paper, regenerate the wraparound cover with a
1.125 in spine instead.

Render the draft cover with:

```sh
rsvg-convert -f pdf \
  -o target/cover/wraparound-cover-450-white.pdf \
  resources/cover/wraparound-cover-450-white.svg

rsvg-convert -f png -w 2200 \
  -o target/cover/wraparound-cover-450-white-preview.png \
  resources/cover/wraparound-cover-450-white.svg
```

Use `resources/docs/blurb.md` as the source copy for the back cover. The final
wraparound width depends on the final page count and KDP paper choice:

```text
cover width = 0.125 + back width + spine width + front width + 0.125
cover height = 0.125 + trim height + 0.125
```

For black-and-white interiors, KDP currently gives spine formulas by paper type:

```text
white paper spine = page count * 0.002252 in
cream paper spine = page count * 0.0025 in
```

Regenerate the KDP cover template after the page count, paper, finish, and
barcode choice are final.
