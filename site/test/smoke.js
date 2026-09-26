// SPDX-License-Identifier: AGPL-3.0-only
// Smoke test: boots the server on a random port and checks routes + security headers.
'use strict';
process.env.PORT = '0';
process.env.NODE_ENV = 'production';
const http = require('node:http');
const assert = require('node:assert');
const { render } = require('../lib/markdown');
const server = require('../server');

const get = (port, path) => new Promise((resolve, reject) => {
  http.get({ host: '127.0.0.1', port, path }, (res) => {
    let body = ''; res.on('data', (c) => (body += c)); res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body }));
  }).on('error', reject);
});

(async () => {
  await new Promise((r) => server.listening ? r() : server.once('listening', r));
  const port = server.address().port;
  let n = 0; const ok = (m) => { n++; console.log('PASS', m); };

  // markdown safety
  const r = render('# T\n\n<script>alert(1)</script>\n\n[x](javascript:alert(1)) `<b>` **b** *i*\n\n| a | b |\n|---|---|\n| 1 | 2 |');
  assert(!r.html.includes('<script>')); assert(!r.html.includes('javascript:')); assert(r.html.includes('&lt;b&gt;'));
  assert(r.html.includes('<table>')); ok('markdown escapes HTML, blocks javascript: links, renders tables');

  const home = await get(port, '/');
  assert.strictEqual(home.status, 200);
  assert(home.headers['content-security-policy'].includes("default-src 'none'"));
  assert(!/<script/i.test(home.body)); ok('landing 200, strict CSP, no <script>');
  assert.strictEqual(home.headers['referrer-policy'], 'no-referrer');
  assert(!home.headers['set-cookie']); ok('no cookies, no-referrer');

  const d = await get(port, '/docs'); assert.strictEqual(d.status, 302); ok('/docs redirects');
  for (const slug of ['introduction', 'install', 'how-it-works', 'cryptography', 'protocol', 'threat-model', 'tor-and-snowflake', 'build-from-source', 'faq']) {
    const p = await get(port, `/docs/${slug}`);
    assert.strictEqual(p.status, 200, slug); assert(p.body.includes('class="prose"'));
  }
  ok('all 9 docs render');

  assert.strictEqual((await get(port, '/../server.js')).status, 404);
  assert.strictEqual((await get(port, '/%2e%2e/server.js')).status, 404);
  assert.strictEqual((await get(port, '/download/..%2fserver.js')).status, 404); ok('path traversal blocked');
  assert.strictEqual((await get(port, '/css/onyx.css')).status, 200); ok('static css');
  assert.strictEqual((await get(port, '/healthz')).body, 'ok'); ok('healthz');
  const sums = await get(port, '/SHA256SUMS'); assert.strictEqual(sums.status, 200); ok('SHA256SUMS');
  assert.strictEqual((await get(port, '/nope')).status, 404); ok('404 page');

  console.log(`\n${n} checks passed`);
  server.close();
})().catch((e) => { console.error('FAIL', e); process.exit(1); });
