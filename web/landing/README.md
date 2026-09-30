# Asher landing page

The public site for Asher: a static page, no build step, no framework.

```
index.html          the page
styles.css          tokens from brands/asher/design/tokens.json, layout, motion
app.js              nav, scene-indicator pill, wallpaper parallax, the looping conversation demo
assets/earth.webp   wallpaper (copy of brands/asher/design/earth.webp)
assets/favicon.svg  orbit ring + dot
assets/og.svg       social preview, source
assets/og.png       social preview, 1200x630 raster of og.svg (what og:image points at)
vercel.json         clean URLs and security headers
robots.txt
```

Fonts (Inter 400/500/600, Space Grotesk 500/600) load from Google Fonts; the
Content-Security-Policy in `vercel.json` allows exactly those two hosts and
nothing else off-origin.

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
