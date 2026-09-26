# ONYX site

The ONYX release site and docs. It's one Node file with **zero npm dependencies**. It sends **no JavaScript** to the browser, sets **no cookies**, loads **nothing from third parties** and keeps **no request logs**.

## Run locally

```bash
node server.js          # http://localhost:8080
npm test                # smoke tests (routes, CSP, path traversal, markdown escaping)
```

## Content

| What | Where |
|---|---|
| APKs to publish | `releases/*.apk`. SHA-256 is computed automatically and shown on the page and at `/SHA256SUMS`. |
| Version, channel, notes, signing cert | `releases/manifest.json` (`"channel": "stable"` hides the preview warning) |
| Screenshots | `public/screenshots/`. Shown in file-name order; the caption comes from the name, so `04-first-message.jpg` → "FIRST MESSAGE". |
| Docs | `docs/NN-slug.md`, rendered at `/docs/slug`. The number sets the sidebar order. |

Before adding screenshots, check them for onion addresses, QR codes and contact names, and blur those.

## Deploy to Railway

```bash
npm i -g @railway/cli
cd site
railway login
railway init            # create a project
railway up              # deploy this folder
railway domain          # get a public https URL
```

Railway detects Node, runs `node server.js`, and checks `/healthz`. APKs in `releases/` are uploaded with the deploy. To update: copy the new APK in, edit `manifest.json`, run `railway up` again.
