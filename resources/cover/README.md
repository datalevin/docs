# Cover Sources

KDP print cover files are separate from the interior manuscript PDF. The final
upload cover should be one wraparound PDF containing, from left to right:

```text
back cover | spine | front cover
```

`front-cover.svg` is only the front panel draft. It is sized at 7.25 in by
9.5 in, which is the current 7 in by 9.25 in trim plus 0.125 in bleed on every
side.

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
