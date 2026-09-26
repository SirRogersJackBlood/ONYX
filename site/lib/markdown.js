// SPDX-License-Identifier: AGPL-3.0-only
// Tiny, dependency-free Markdown renderer for the ONYX docs.
// Raw HTML in Markdown is NEVER passed through: everything is escaped first.
// Supports: ATX headings (with anchors), paragraphs, **bold**, *italic*, `code`,
// [links](url), ![images](src), fenced code, blockquotes (+ callouts), ordered/unordered
// lists (nested by indentation), GFM tables, horizontal rules.
'use strict';

const escapeHtml = (s) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

const slugify = (s) =>
  s.toLowerCase().replace(/<[^>]+>/g, '').replace(/&[a-z]+;/g, '')
    .replace(/[^a-z0-9\s-]/g, '').trim().replace(/\s+/g, '-').replace(/-+/g, '-');

function safeUrl(u) {
  const url = u.trim();
  // Only relative links, in-page anchors, https and mailto. No javascript:, data:, etc.
  if (/^(https:\/\/|mailto:|\/|#|\.\/|\.\.\/)/i.test(url) || /^[\w.-]+(\.md)?(#[\w-]+)?$/.test(url)) {
    return url.replace(/\.md(#|$)/, '$1');
  }
  return '#';
}

function inline(text) {
  // Protect code spans first.
  const codes = [];
  let s = text.replace(/`([^`]+)`/g, (_, c) => { codes.push(c); return `\u0000${codes.length - 1}\u0000`; });
  s = escapeHtml(s);
  s = s.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, (_, alt, src) =>
    `<img src="${escapeHtml(safeUrl(src))}" alt="${alt}" loading="lazy">`);
  s = s.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_, label, href) => {
    const h = safeUrl(href.replace(/&amp;/g, '&'));
    const ext = h.startsWith('https://');
    return `<a href="${escapeHtml(h)}"${ext ? ' rel="noopener noreferrer"' : ''}>${label}</a>`;
  });
  s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  s = s.replace(/(^|[^*\w])\*([^*\n]+)\*(?!\w)/g, '$1<em>$2</em>');
  s = s.replace(/\u0000(\d+)\u0000/g, (_, i) => `<code>${escapeHtml(codes[+i])}</code>`);
  return s;
}

function renderTable(lines) {
  const split = (l) => l.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
  const head = split(lines[0]);
  const aligns = split(lines[1]).map((c) => (/^:-+:$/.test(c) ? 'center' : /-+:$/.test(c) ? 'right' : ''));
  const cell = (tag, c, i) => `<${tag}${aligns[i] ? ` style="text-align:${aligns[i]}"` : ''}>${inline(c)}</${tag}>`;
  let html = '<div class="table"><table><thead><tr>' + head.map((c, i) => cell('th', c, i)).join('') + '</tr></thead><tbody>';
  for (const l of lines.slice(2)) html += '<tr>' + split(l).map((c, i) => cell('td', c, i)).join('') + '</tr>';
  return html + '</tbody></table></div>';
}

function renderList(lines) {
  // lines: raw list lines (possibly nested by indentation)
  const items = [];
  for (const l of lines) {
    const m = l.match(/^(\s*)([-*+]|\d+\.)\s+(.*)$/);
    if (m) items.push({ indent: m[1].length, ordered: /\d/.test(m[2]), text: m[3] });
    else if (items.length) items[items.length - 1].text += ' ' + l.trim();
  }
  let i = 0;
  function build(level) {
    const ordered = items[i].ordered;
    let html = ordered ? '<ol>' : '<ul>';
    while (i < items.length && items[i].indent >= level) {
      if (items[i].indent > level) { html = html.replace(/<\/li>$/, '') + build(items[i].indent) + '</li>'; continue; }
      const t = items[i].text;
      const task = t.match(/^\[( |x)\]\s+(.*)$/i);
      html += task
        ? `<li class="task${task[1] !== ' ' ? ' done' : ''}">${inline(task[2])}</li>`
        : `<li>${inline(t)}</li>`;
      i++;
    }
    return html + (ordered ? '</ol>' : '</ul>');
  }
  return build(items[0].indent);
}

/** Returns { html, title, toc: [{level, id, text}] } */
function render(md) {
  const lines = md.replace(/\r\n?/g, '\n').split('\n');
  const out = [];
  const toc = [];
  const used = new Set();
  let title = null;
  let i = 0;

  const isBlockStart = (l) =>
    /^#{1,6}\s/.test(l) || /^```/.test(l) || /^>\s?/.test(l) || /^\s*([-*+]|\d+\.)\s+/.test(l) ||
    /^(-{3,}|\*{3,})\s*$/.test(l) || (/^\|/.test(l));

  while (i < lines.length) {
    const line = lines[i];

    if (/^\s*$/.test(line)) { i++; continue; }

    let m;
    if ((m = line.match(/^```\s*([\w+-]*)\s*$/))) {
      const lang = m[1];
      const buf = [];
      i++;
      while (i < lines.length && !/^```\s*$/.test(lines[i])) buf.push(lines[i++]);
      i++;
      out.push(`<pre class="code"${lang ? ` data-lang="${escapeHtml(lang)}"` : ''}><code>${escapeHtml(buf.join('\n'))}</code></pre>`);
      continue;
    }

    if ((m = line.match(/^(#{1,6})\s+(.*?)\s*#*$/))) {
      const level = m[1].length;
      const content = inline(m[2]);
      let id = slugify(m[2]) || 'section';
      let n = 2; const base = id;
      while (used.has(id)) id = `${base}-${n++}`;
      used.add(id);
      if (level === 1 && !title) { title = m[2].replace(/[`*]/g, ''); out.push(`<h1 id="${id}">${content}</h1>`); }
      else {
        if (level <= 3) toc.push({ level, id, text: m[2].replace(/[`*]/g, '') });
        out.push(`<h${level} id="${id}"><a class="anchor" href="#${id}" aria-hidden="true">#</a>${content}</h${level}>`);
      }
      i++; continue;
    }

    if (/^(-{3,}|\*{3,})\s*$/.test(line)) { out.push('<hr>'); i++; continue; }

    if (/^>\s?/.test(line)) {
      const buf = [];
      while (i < lines.length && /^>\s?/.test(lines[i])) buf.push(lines[i++].replace(/^>\s?/, ''));
      const inner = render(buf.join('\n')).html;
      const kind = (buf[0].match(/^\*\*(Note|Warning|Tip|Important|Danger)\*\*/i) || [])[1];
      out.push(`<blockquote${kind ? ` class="callout ${kind.toLowerCase()}"` : ''}>${inner}</blockquote>`);
      continue;
    }

    if (/^\|/.test(line) && i + 1 < lines.length && /^\|?\s*:?-{2,}/.test(lines[i + 1])) {
      const buf = [];
      while (i < lines.length && /^\|/.test(lines[i])) buf.push(lines[i++]);
      out.push(renderTable(buf));
      continue;
    }

    if (/^\s*([-*+]|\d+\.)\s+/.test(line)) {
      const buf = [];
      while (i < lines.length && (/^\s*([-*+]|\d+\.)\s+/.test(lines[i]) || (/^\s{2,}\S/.test(lines[i]) && buf.length))) buf.push(lines[i++]);
      out.push(renderList(buf));
      continue;
    }

    const buf = [];
    while (i < lines.length && !/^\s*$/.test(lines[i]) && !(buf.length && isBlockStart(lines[i]))) buf.push(lines[i++]);
    out.push(`<p>${inline(buf.join(' '))}</p>`);
  }

  return { html: out.join('\n'), title, toc };
}

module.exports = { render, escapeHtml, slugify };
