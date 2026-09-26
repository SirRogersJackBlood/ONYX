// SPDX-License-Identifier: AGPL-3.0-only
// ONYX release site. Zero npm dependencies, zero client-side JavaScript, zero third-party requests.
// Deploys to Railway as-is (reads PORT from the environment).
'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { render } = require('./lib/markdown');
const views = require('./lib/views');

const ROOT = __dirname;
const PUBLIC = path.join(ROOT, 'public');
const DOCS = path.join(ROOT, 'docs');
const RELEASES = process.env.RELEASES_DIR || path.join(ROOT, 'releases');
const PORT = process.env.PORT !== undefined ? Number(process.env.PORT) : 8080;

const MIME = {
  '.css': 'text/css; charset=utf-8', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
  '.webp': 'image/webp', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.txt': 'text/plain; charset=utf-8',
  '.woff2': 'font/woff2', '.asc': 'text/plain; charset=utf-8', '.json': 'application/json; charset=utf-8',
};

// Security headers: nothing loads from anywhere but this origin, and no scripts run at all.
const SECURITY_HEADERS = {
  'Content-Security-Policy':
    "default-src 'none'; img-src 'self'; style-src 'self'; font-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
  'Referrer-Policy': 'no-referrer',
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), interest-cohort=(), browsing-topics=()',
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Cross-Origin-Resource-Policy': 'same-origin',
  'Strict-Transport-Security': 'max-age=63072000; includeSubDomains',
};

// ---------------------------------------------------------------- docs

function loadDocs() {
  const files = fs.existsSync(DOCS) ? fs.readdirSync(DOCS).filter((f) => /^\d+-[\w-]+\.md$/.test(f)).sort() : [];
  return files.map((f) => {
    const md = fs.readFileSync(path.join(DOCS, f), 'utf8');
    const r = render(md);
    const slug = f.replace(/^\d+-/, '').replace(/\.md$/, '');
    return { slug, file: f, title: r.title || slug, html: r.html, toc: r.toc };
  });
}
let docs = loadDocs();
if (process.env.NODE_ENV !== 'production') {
  // Hot reload in dev so editing Markdown shows up on refresh.
  fs.watch(DOCS, () => { try { docs = loadDocs(); } catch { /* keep last good */ } });
}

// ---------------------------------------------------------------- releases

const hashCache = new Map(); // path -> {mtimeMs, sha256}
function sha256Of(file) {
  const st = fs.statSync(file);
  const hit = hashCache.get(file);
  if (hit && hit.mtimeMs === st.mtimeMs) return hit.sha256;
  const sha256 = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
  hashCache.set(file, { mtimeMs: st.mtimeMs, sha256 });
  return sha256;
}

function listReleases() {
  if (!fs.existsSync(RELEASES)) return { apks: [], manifest: {} };
  let manifest = {};
  const mf = path.join(RELEASES, 'manifest.json');
  if (fs.existsSync(mf)) { try { manifest = JSON.parse(fs.readFileSync(mf, 'utf8')); } catch { manifest = {}; } }
  const apks = fs.readdirSync(RELEASES)
    .filter((f) => /^[\w.+-]+\.apk$/.test(f))
    .map((name) => {
      const p = path.join(RELEASES, name);
      const st = fs.statSync(p);
      return { name, size: st.size, date: st.mtime, sha256: sha256Of(p), abi: (name.match(/(arm64-v8a|armeabi-v7a|x86_64|universal)/) || [])[1] || 'universal' };
    })
    .sort((a, b) => b.date - a.date || a.name.localeCompare(b.name));
  return { apks, manifest };
}

function listScreenshots() {
  const dir = path.join(PUBLIC, 'screenshots');
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir).filter((f) => /\.(png|jpe?g|webp)$/i.test(f)).sort()
    .map((f) => ({ src: `/screenshots/${encodeURIComponent(f)}`, alt: f.replace(/\.[^.]+$/, '').replace(/^\d+[-_ ]*/, '').replace(/[-_]+/g, ' ') }));
}

// ---------------------------------------------------------------- http

function send(res, status, body, headers = {}) {
  res.writeHead(status, { ...SECURITY_HEADERS, ...headers });
  res.end(body);
}
const html = (res, body, status = 200) =>
  send(res, status, body, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache' });

function serveFile(res, file, extraHeaders = {}) {
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) return html(res, views.notFound(docs), 404);
    const type = MIME[path.extname(file).toLowerCase()] || 'application/octet-stream';
    res.writeHead(200, { ...SECURITY_HEADERS, 'Content-Type': type, 'Content-Length': st.size,
      'Cache-Control': 'public, max-age=3600', ...extraHeaders });
    fs.createReadStream(file).pipe(res);
  });
}

/** Resolve a URL path inside a base dir; null if it tries to escape. */
function inside(base, rel) {
  const p = path.normalize(path.join(base, rel));
  return p.startsWith(base + path.sep) ? p : null;
}

const server = http.createServer((req, res) => {
  // Deliberately no request logging: we do not record IPs, user agents or paths.
  if (req.method !== 'GET' && req.method !== 'HEAD') return send(res, 405, 'Method Not Allowed', { Allow: 'GET, HEAD' });

  let url;
  try { url = new URL(req.url, 'http://x'); } catch { return send(res, 400, 'Bad Request'); }
  const p = decodeURIComponent(url.pathname);

  if (p === '/') {
    const { apks, manifest } = listReleases();
    return html(res, views.landing({ apks, manifest, screenshots: listScreenshots(), docs }));
  }
  if (p === '/healthz') return send(res, 200, 'ok', { 'Content-Type': 'text/plain' });

  if (p === '/docs' || p === '/docs/') return docs.length ? send(res, 302, '', { Location: `/docs/${docs[0].slug}` }) : html(res, views.notFound(docs), 404);
  let m;
  if ((m = p.match(/^\/docs\/([\w-]+)\/?$/))) {
    const idx = docs.findIndex((d) => d.slug === m[1]);
    if (idx < 0) return html(res, views.notFound(docs), 404);
    return html(res, views.doc({ docs, idx }));
  }

  if (p === '/SHA256SUMS' || p === '/SHA256SUMS.txt') {
    const { apks, manifest } = listReleases();
    const rows = apks.map((a) => `${a.sha256}  ${a.name}`)
      .concat((Array.isArray(manifest.assets) ? manifest.assets : []).map((a) => `${a.sha256}  ${a.file}`));
    const body = rows.join('\n') + '\n';
    return send(res, 200, body, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-cache' });
  }

  if ((m = p.match(/^\/download\/([\w.+-]+\.apk)$/))) {
    const file = inside(RELEASES, m[1]);
    if (!file) return html(res, views.notFound(docs), 404);
    return serveFile(res, file, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Disposition': `attachment; filename="${m[1]}"`,
      'Cache-Control': 'no-cache',
    });
  }

  const file = inside(PUBLIC, p.replace(/^\/+/, ''));
  if (file) return serveFile(res, file);
  return html(res, views.notFound(docs), 404);
});

server.listen(PORT, () => {
  process.stdout.write(`ONYX site listening on :${PORT} (${docs.length} docs)\n`);
});

module.exports = server;
