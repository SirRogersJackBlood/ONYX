// SPDX-License-Identifier: AGPL-3.0-only
// HTML views. Server-rendered, no client-side JavaScript.
'use strict';

const { escapeHtml: e } = require('./markdown');

const fmtSize = (b) => (b >= 1e6 ? `${(b / 1e6).toFixed(1)} MB` : `${Math.round(b / 1e3)} KB`);
const fmtDate = (d) => d.toISOString().slice(0, 10);

function shell({ title, description, body, bodyClass = '' }) {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${e(title)}</title>
<meta name="description" content="${e(description)}">
<meta name="referrer" content="no-referrer">
<meta name="color-scheme" content="dark">
<meta name="theme-color" content="#000000">
<link rel="icon" href="/img/favicon.png" type="image/png">
<link rel="stylesheet" href="/css/onyx.css">
</head>
<body class="${bodyClass}">
${body}
</body>
</html>`;
}

function nav(active = '') {
  const link = (href, label, key) => `<a href="${href}"${active === key ? ' aria-current="page"' : ''}>${label}</a>`;
  return `<header class="topbar">
  <a class="brand" href="/"><img src="/img/ring.png" alt="" width="28" height="28"><span>ONYX</span></a>
  <nav>
    ${link('/#how', 'How it works', 'how')}
    ${link('/#privacy', 'Privacy', 'privacy')}
    ${link('/docs', 'Docs', 'docs')}
    ${link('/#download', 'Download', 'download')}
    <a href="https://github.com/SirRogersJackBlood/ONYX" rel="noopener noreferrer">Source</a>
  </nav>
</header>`;
}

function footer() {
  return `<footer class="footer">
  <div class="footer-author">
    <img src="/img/raven.png" alt="Raven emblem" width="72" height="72">
    <div><div class="mono dim">DESIGN BY</div><div class="mono">SIRROGERSJACKBLOOD</div></div>
  </div>
  <div class="footer-meta">
    <p>ONYX is free software under the GNU AGPL-3.0.</p>
    <p><a href="https://github.com/SirRogersJackBlood/ONYX" rel="noopener noreferrer">Source on GitHub</a> · <a href="https://github.com/SirRogersJackBlood/ONYX/releases" rel="noopener noreferrer">Releases</a></p>
    <p class="mono dim">This site sets no cookies, runs no JavaScript, loads nothing from third parties and keeps no request logs.</p>
  </div>
</footer>`;
}

// ------------------------------------------------------------------ landing

function landing({ apks, manifest, screenshots, docs }) {
  const latest = apks[0];
  const preview = (manifest.channel || '').toLowerCase() !== 'stable';
  const version = manifest.version ? ` ${e(manifest.version)}` : '';

  const releasesUrl = manifest.releases_url || '';
  const heroCta = (latest || releasesUrl)
    ? `<a class="btn primary" href="#download">Download${version}</a>`
    : `<span class="btn primary disabled">F-Droid release coming</span>`;
  const ghBlock = releasesUrl ? `
    <div class="apk gh">
      <div class="apk-head">
        <a class="btn primary" href="${e(releasesUrl)}" rel="noopener noreferrer">GitHub Releases${version}</a>
        <span class="mono dim">APKs per device type · SHA-256 in the release notes</span>
      </div>
      ${manifest.repo_url ? `<p class="dim small">Source: <a href="${e(manifest.repo_url)}" rel="noopener noreferrer">${e(manifest.repo_url.replace('https://', ''))}</a></p>` : ''}
    </div>` : '';

  const gallery = screenshots.length
    ? `<div class="gallery">${screenshots.map((s) => `<figure><a href="${s.src}"><img src="${s.src}" alt="${e(s.alt)}" loading="lazy"></a><figcaption>${e(s.alt)}</figcaption></figure>`).join('')}</div>`
    : `<p class="dim center">Screenshots coming soon.</p>`;

  const apkRows = apks.map((a) => `
      <div class="apk">
        <div class="apk-head">
          <a class="btn ${a === latest ? 'primary' : 'ghost'}" href="/download/${encodeURIComponent(a.name)}" download>${e(a.abi === 'universal' ? 'Universal APK' : a.abi)}</a>
          <span class="mono dim">${e(a.name)} · ${fmtSize(a.size)} · ${fmtDate(a.date)}</span>
        </div>
        <div class="hash"><span class="mono dim">SHA-256</span><code>${a.sha256}</code></div>
      </div>`).join('');

  const downloadBlock = (apks.length || releasesUrl) ? `
    ${preview ? `<div class="callout warning"><p><strong>Preview build.</strong> ${e(manifest.notes || 'Signed with a development key for testing. The F-Droid release will be built and signed by F-Droid from source.')}</p></div>` : ''}
    <div class="apks">${ghBlock}${apkRows}</div>
    ${manifest.signer_sha256 ? `<div class="hash signer"><span class="mono dim">SIGNING CERT SHA-256</span><code>${e(manifest.signer_sha256)}</code></div>` : ''}
    <p class="dim">Most phones since 2017 need <strong>arm64-v8a</strong>.${apks.length ? ' Hashes of files hosted here: <a href="/SHA256SUMS">/SHA256SUMS</a>' : ''}</p>`
    : `<p class="dim">No build published yet. ONYX will be available on F-Droid.</p>`;

  const body = `
${nav()}
<main>
  <section class="hero">
    <div class="hero-grid" aria-hidden="true"></div>
    <img class="hero-emblem" src="/img/emblem.png" alt="ONYX — Crucible Engine" width="440" height="440">
    <h1 class="sr-only">ONYX</h1>
    <p class="tagline">Messages with no phone number, no account and no server.</p>
    <p class="sub">Onion-to-onion over Tor. Post-quantum end-to-end encryption. Nothing in between.</p>
    <div class="cta">${heroCta}<a class="btn ghost" href="/docs">Read the docs</a></div>
    <p class="mono dim strip">NO SERVERS · NO PHONE NUMBERS · NO GOOGLE SERVICES · AGPL-3.0</p>
  </section>

  <section id="how" class="section">
    <p class="kicker">HOW IT WORKS</p>
    <h2>Meet. Scan. Talk.</h2>
    <div class="steps">
      <div class="step"><span class="num">01</span><h3>Meet in person</h3><p>One phone shows a one-time QR code. It expires in ten minutes and works once.</p></div>
      <div class="step"><span class="num">02</span><h3>Scan</h3><p>The other phone connects to its onion address over Tor, checks the identity key against the code, and runs a post-quantum key exchange.</p></div>
      <div class="step"><span class="num">03</span><h3>Talk over Tor</h3><p>Every message goes straight from one phone's onion service to the other's. No server stores, routes or sees it.</p></div>
    </div>
  </section>

  <section class="section specs">
    <div class="spec"><span class="mono">PQXDH</span><p>X25519 + Kyber-1024 key agreement</p></div>
    <div class="spec"><span class="mono">TRIPLE RATCHET</span><p>Double Ratchet + SPQR (ML-KEM-768)</p></div>
    <div class="spec"><span class="mono">TOR v3 ONION</span><p>Each phone is its own hidden service</p></div>
    <div class="spec"><span class="mono">FIXED BUCKETS</span><p>Every message and frame padded to fixed sizes</p></div>
    <div class="spec"><span class="mono">HARDWARE VAULT</span><p>AES-256-GCM, key held by StrongBox / TEE</p></div>
  </section>

  <section id="privacy" class="section">
    <p class="kicker">PRIVACY</p>
    <h2>Same encryption as Signal. Nothing around it.</h2>
    <p class="lead">ONYX uses Signal's own library for the cryptography, since it is the most scrutinised design there is. What changes is everything around it.</p>
    <div class="table"><table>
      <thead><tr><th></th><th>Signal</th><th>ONYX</th></tr></thead>
      <tbody>
        <tr><td>Identifier</td><td>Phone number</td><td>None. A key exchanged in person.</td></tr>
        <tr><td>Servers</td><td>Central servers see your IP and timing</td><td>None. Phone to phone over Tor.</td></tr>
        <tr><td>Who sees your IP</td><td>Signal's servers</td><td>Nobody</td></tr>
        <tr><td>Contact discovery</td><td>Hashed numbers in a server enclave</td><td>None exists</td></tr>
        <tr><td>First-contact check</td><td>Optional safety number</td><td>Built into the QR code</td></tr>
        <tr><td>Push notifications</td><td>Google FCM on stock builds</td><td>None</td></tr>
        <tr><td>Message encryption</td><td>PQXDH + Triple Ratchet</td><td>PQXDH + Triple Ratchet (libsignal)</td></tr>
      </tbody>
    </table></div>
    <p class="dim small">The trade-off: both phones need to be online, or the recipient needs a mailbox device, for delivery. <a href="/docs/threat-model">Read the threat model</a>.</p>
  </section>

  <section id="screens" class="section">
    <p class="kicker">SCREENS</p>
    <h2>Obsidian by design.</h2>
    ${gallery}
  </section>

  <section id="forge" class="section forge">
    <div class="forge-art"><img src="/img/sigil.jpg" alt="The /FORGE sigil" width="360" height="360" loading="lazy"></div>
    <div>
      <p class="kicker">/FORGE RANK</p>
      <h2>Help others reach Tor.</h2>
      <p>Turn on a Snowflake proxy and your phone helps people in censored countries connect to Tor. It runs only on unmetered Wi-Fi while charging, and it is never an exit node.</p>
      <ol class="tiers">
        <li><span>Ember</span><em>turned on</em></li>
        <li><span>Spark</span><em>10 h or 25 helped</em></li>
        <li><span>Flame</span><em>50 h or 250</em></li>
        <li><span>Forge</span><em>200 h or 1,000</em></li>
        <li><span>Crucible</span><em>1,000 h or 5,000</em></li>
      </ol>
      <p class="dim">Your rank is visible only to the people you chat with, inside the encrypted session. No leaderboard, no server, no public profile.</p>
    </div>
  </section>

  <section id="download" class="section">
    <p class="kicker">DOWNLOAD</p>
    <h2>Get ONYX.</h2>
    ${downloadBlock}
    <details class="verify">
      <summary>How to verify your download</summary>
      <p>Compare the SHA-256 above with the file you downloaded:</p>
      <pre class="code"><code># Windows (PowerShell)
Get-FileHash .\\app-arm64-v8a-release.apk -Algorithm SHA256

# Linux / macOS
sha256sum app-arm64-v8a-release.apk</code></pre>
      <p>Then check the signing certificate with <code>apksigner verify --print-certs</code>. Full steps: <a href="/docs/install">Install &amp; verify</a>.</p>
    </details>
  </section>
</main>
${footer()}`;

  return shell({
    title: 'ONYX · Private messaging over Tor',
    description: 'ONYX: private peer-to-peer messaging for Android over Tor. No phone numbers, no accounts, no servers. Post-quantum end-to-end encryption.',
    body,
    bodyClass: 'landing',
  });
}

// ------------------------------------------------------------------ docs

function doc({ docs, idx }) {
  const d = docs[idx];
  const prev = docs[idx - 1];
  const next = docs[idx + 1];
  const side = docs.map((x) => `<a href="/docs/${x.slug}"${x === d ? ' aria-current="page"' : ''}>${e(x.title)}</a>`).join('');
  const toc = d.toc.filter((t) => t.level >= 2);
  const body = `
${nav('docs')}
<div class="docs">
  <aside class="sidebar">
    <div class="side-desktop"><p class="mono dim">DOCUMENTATION</p><nav>${side}</nav></div>
    <details class="side-mobile">
      <summary class="mono dim">DOCUMENTATION · ${e(d.title.toUpperCase())}</summary>
      <nav>${side}</nav>
    </details>
  </aside>
  <article class="prose">
    ${d.html}
    <nav class="pager">
      ${prev ? `<a class="prev" href="/docs/${prev.slug}"><span class="mono dim">PREVIOUS</span>${e(prev.title)}</a>` : '<span></span>'}
      ${next ? `<a class="next" href="/docs/${next.slug}"><span class="mono dim">NEXT</span>${e(next.title)}</a>` : '<span></span>'}
    </nav>
  </article>
  <aside class="toc">
    ${toc.length ? `<p class="mono dim">ON THIS PAGE</p><nav>${toc.map((t) => `<a class="l${t.level}" href="#${t.id}">${e(t.text)}</a>`).join('')}</nav>` : ''}
  </aside>
</div>
${footer()}`;
  return shell({ title: `${d.title} · ONYX docs`, description: `ONYX documentation: ${d.title}`, body, bodyClass: 'docs-page' });
}

function notFound(docs) {
  const body = `${nav()}<main class="section center nf"><p class="kicker">404</p><h2>Nothing here.</h2><p class="dim">That page doesn't exist.</p><p><a class="btn ghost" href="/">Home</a> ${docs.length ? '<a class="btn ghost" href="/docs">Docs</a>' : ''}</p></main>${footer()}`;
  return shell({ title: 'Not found · ONYX', description: 'Not found', body });
}

module.exports = { landing, doc, notFound };
