# Asher landing page

The public site for Asher: a static page, no build step, no framework.

```
index.html          the page: hero, four numbered chapters, an interlude, footer
styles.css          tokens from brands/asher/design/tokens.json, editorial grid, motion
app.js              masthead, mobile sheet, chapter reveal, connection-state tabs
scene.js            the planet: three.js Earth with night lights, clouds, Fresnel atmosphere,
                    orbit ring, relayed-packet mesh; scroll choreography; reduced-motion still
vendor/three/       three.module.min.js 0.170.0 (MIT, LICENSE alongside), vendored so the CSP
                    stays script-src 'self'
assets/earth/       day, normal, specular, lights, clouds textures (NASA Visible Earth via the
                    three.js examples, converted to WebP; about 800 KB, loaded after first paint)
assets/earth-poster.webp  a still of the scene, painted before WebGL is ready or without it
assets/favicon.svg  orbit ring + dot
assets/og.svg       social preview, source
assets/og.png       social preview, 1200x630 raster of og.svg (what og:image points at)
vercel.json         clean URLs and security headers
robots.txt
```

Fonts (IBM Plex Sans 300/400/500, IBM Plex Mono 400/500) load from Google
Fonts; the Content-Security-Policy in `vercel.json` allows exactly those two
hosts and nothing else off-origin.

## Design rules the page follows

The redesign was checked against the common tells of generated landing pages
(default sans + purple gradient, gradient headline text, glass panels, blob
backgrounds, icon-in-a-rounded-square feature cards, the centred hero plus
three identical cards, a badge above the H1, six identical download cards):

- one typeface family with a mono companion, light display weights, tight
  tracking, an italic second line instead of a colour gradient;
- a 12-column editorial grid, chapter numbers in the margin, rule-separated
  rows and tables instead of cards, left-aligned copy with a measure;
- one accent colour, used for links, the active state and the atmosphere;
- specific copy with real numbers, and the product's own conversation UI as
  the only illustration besides the planet;
- one entrance fade per chapter, a packet moving along the route, and a
  planet whose position is driven by scroll. Nothing bounces, nothing glows
  unprompted, and `prefers-reduced-motion` renders a still frame.

## Checking it locally

```sh
cd web/landing && python3 -m http.server 8765
```

WebGL needs a real browser; with `--use-gl=angle --use-angle=swiftshader`
headless Chromium renders it too, which is how `assets/earth-poster.webp`
was produced (a 1920x1080 capture of the hero with the copy hidden).

## Deploy

**With the Vercel CLI**, from this directory:

```sh
cd web/landing
vercel            # preview
vercel --prod     # production
```

There is no framework preset and no build command; answer "no" to any
prompt that asks for one, or accept the "Other" preset. Output directory is
`.` (the project root).

**By importing the repository** in the Vercel dashboard: add the project,
set **Root Directory** to `web/landing`, leave Framework Preset as
"Other", Build Command empty and Output Directory empty. Every push to the
production branch then redeploys the page.

`vercel.json` sets `cleanUrls` and the response headers (CSP, HSTS,
`X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`); the
canonical URL in `index.html` is `https://asher.messenger/` and should be
changed if the site is served elsewhere.

## Notes

* The wallpaper is the 960x540 source from `brands/asher/design/`. Swap in
  the 2560x1440 master at the same path for production, as `tokens.json`
  asks; nothing else needs to change.
* Download buttons point at `.../releases/latest` and are labelled "Coming
  with the first release". When the first release is cut, change the label
  in `index.html` (search for `btn-muted`) and the class to `btn-secondary`.
* Every product claim on the page comes from `docs/offline-mesh.md`,
  `docs/security-audit.md` and the repository README. Update the page when
  those change.
* Preview locally with any static server, for example
  `python3 -m http.server 8080` in this directory.
