// JS port of wikantik-main com.wikantik.markdown.extensions.fences.MermaidFenceRenderer's safety policy. The
// editor previews never reach the server, so the same fail-closed rules run here; the reader view re-runs them
// too, and for a hand-written envelope (allowHTML on) that never met the server policy they are the only gate —
// see drawFence.js. Parity with the Java is enforced by the shared fixture
// (src/utils/__fixtures__/renderedFences.json, `mermaidPolicy`) — change both sides together, and add a case.
//
// Port notes (where JS and Java semantics differ and this file compensates):
//   - Java's regex `\s` (no UNICODE_CHARACTER_CLASS) is ASCII `[ \t\n\x0B\f\r]`; JS `\s` is Unicode, so every
//     class here is spelled out.
//   - Java's CASE_INSENSITIVE | UNICODE_CASE folds e.g. U+017F (long s) to `s`; the `iu` flags do the same.
//   - Java's possessive quantifiers have no JS form. Every pattern below alternates disjoint classes, so a
//     greedy backtrack costs O(1) per step and stays linear; trimming is done with loops, never `\s+$`.
//   - Java walks UTF-16 code units, so an astral format character (a surrogate pair) is never normalised; the
//     `c.length === 1` guards keep that.
//   - String.strip()/isBlank() use Character.isWhitespace (no NBSP / U+2007 / U+202F); see isJavaWhitespace.
//   - Page names are decoded the way java.net.URLDecoder (JDK 25) does it — ASCII hex pairs only, U+FFFD for
//     malformed UTF-8, an incomplete escape refuses tracking — not with decodeURIComponent (which throws on
//     malformed UTF-8).

const WS = '[ \\t\\n\\v\\f\\r]';
const NWS = '[^ \\t\\n\\v\\f\\r]';
const CLICK_OK = new RegExp(
  `^(?:click|link)${WS}+${NWS}+${WS}+(?:href${WS}+)?"([^"]*)"(?:${WS}+"[^"]*")?(?:${WS}+(?:_self|_blank|_parent|_top))?$`,
  'iu');
const CLASS_LINK = new RegExp(`^link${WS}+${NWS}+${WS}+(?:href${WS}+)?".*$`, 'iu');
const CALLBACK_LINE = new RegExp(`^callback${WS}+[^ \\t\\n\\v\\f\\r\\-=.<>~].*$`, 'iu');
const JSON_VALUE = new RegExp(`"[^"]*"${WS}*:${WS}*"([^"]*)"`, 'g');
const QUOTED = /"([^"]*)"/g;
const ENTITY = /&#[xX]([0-9a-fA-F]+);?|&#([0-9]+);?|&(colon|Tab|NewLine|sol);|#([0-9]+);/g;
const NAMED_ENTITIES = { colon: ':', Tab: '\t', NewLine: '\n', sol: '/' };

// The control-character classes below are deliberate: they are ports of Java's Character predicates.
// Java: Character.isWhitespace || isSpaceChar || c == U+0085 || getType == FORMAT (CR/LF handled first).
// eslint-disable-next-line no-control-regex -- U+001C..U+001F are Java whitespace
const NORMALISE = /[\t\x0B\f\x1C-\x1F\x85\p{Zs}\p{Zl}\p{Zp}\p{Cf}]/gu;
// Java startsFrontMatter skip set: isWhitespace || isSpaceChar || isISOControl || FORMAT (|| BOM, a Cf).
// eslint-disable-next-line no-control-regex -- Character.isISOControl
const FRONT_MATTER_SKIP = /[\x00-\x1F\x7F-\x9F\p{Zs}\p{Zl}\p{Zp}\p{Cf}]/u;
// eslint-disable-next-line no-control-regex -- chars <= U+0020 and U+007F..U+009F, as the Java backstop drops
const CONTROL_OR_SPACE = /[\x00-\x20\x7F-\x9F]/g;
// eslint-disable-next-line no-control-regex -- chars a browser ignores inside a URL scheme, as safeUrl drops
const URL_IGNORED = /[\x00-\x20\x7F-\x9F\u200B\u00AD]/g;

/**
 * Applies the Mermaid fence safety policy.
 *
 * @param {string|null|undefined} source the fence's text exactly as stored
 * @returns {{ allowed: boolean, reason: string|null, pageLinks: string[] }} `reason` is one of `callback`,
 *   `unsafe-link`, `unsafe-config` or `render-failed` when refused; `pageLinks` lists the `/wiki/PageName`
 *   targets (decoded, each once, in source order) and is empty when refused.
 */
export function checkMermaid(source) {
  try {
    if (source == null || source === '') return { allowed: true, reason: null, pageLinks: [] };
    return analyse(String(source));
  } catch (e) {
    console.warn('[fences] Mermaid policy check failed unexpectedly; refusing the fence', e);
    return { allowed: false, reason: 'render-failed', pageLinks: [] };
  }
}

function refused(reason) {
  return { allowed: false, reason, pageLinks: [] };
}

function analyse(rawSource) {
  const links = new Set();
  const source = normalise(rawSource);
  if (source.includes('%%{') || startsFrontMatter(rawSource) || startsFrontMatter(source)) {
    return refused('unsafe-config');
  }
  if (hasScriptScheme(source)) return refused('unsafe-link');
  for (const line of source.split('\n')) {
    // Safety runs over the UNION of quote-aware statements and naive ';' pieces (Ruling I): Mermaid tokens
    // such as sequence message text end at ';' regardless of quotes. Page links come from quote-aware
    // statements only.
    for (const statement of splitStatements(line)) {
      const v = checkStatement(javaStrip(statement), links);
      if (v) return refused(v);
    }
    for (const piece of line.split(';')) {
      const v = checkStatement(javaStrip(piece), null);
      if (v) return refused(v);
    }
  }
  return { allowed: true, reason: null, pageLinks: [...links] };
}

/** The click/callback/link/links rules for one statement: the refusal reason, or null when safe. */
function checkStatement(s, links) {
  const urls = [];
  const first = firstWord(s);
  if (first === 'click') {
    const m = CLICK_OK.exec(s);
    if (!m) return 'callback';
    urls.push(m[1]);
  } else if (first === 'callback' && CALLBACK_LINE.test(s)) {
    return 'callback';
  } else if (first === 'link' || first === 'links') {
    const rest = s.slice(first.length);
    const colon = rest.indexOf(':');
    const at = colon < 0 ? -1 : rest.indexOf('@', colon + 1);
    if (first === 'link' && at >= 0) {
      // sequence link: the URL follows the FIRST '@' after the actor's ':'
      urls.push(javaStrip(rest.slice(at + 1)));
    } else if (first === 'link' && CLASS_LINK.test(s)) {
      const m = CLICK_OK.exec(s);
      if (!m) return 'unsafe-link';
      urls.push(m[1]);
    } else if (first === 'links' && colon >= 0 && javaStrip(rest.slice(colon + 1)).startsWith('{')) {
      const json = rest.slice(colon + 1);
      if (json.includes('\\')) return 'unsafe-link';
      for (const m of json.matchAll(JSON_VALUE)) urls.push(m[1]);
    }
  }
  if ((first === 'click' || first === 'link' || first === 'links') && !quotedStringsSafe(s)) {
    return 'unsafe-link';
  }
  const names = [];
  for (const url of urls) {
    const decoded = javaStrip(decodeEntities(url));
    if (!safeUrl(decoded)) return 'unsafe-link';
    const name = pageName(decoded);
    if (name !== null) names.push(name);
  }
  if (links) names.forEach((n) => links.add(n));
  return null;
}

/** Leading ASCII letters of the statement, lower-cased, when followed by a space or the end; else ''. */
function firstWord(s) {
  let i = 0;
  while (i < s.length) {
    const c = s.charCodeAt(i) | 0x20;
    if (c < 0x61 || c > 0x7a) break;
    i++;
  }
  return i === 0 || (i < s.length && s[i] !== ' ') ? '' : s.slice(0, i).toLowerCase();
}

/** CRLF/CR become LF; every other whitespace, space-separator or (BMP) format character becomes a space. */
function normalise(s) {
  return s.replace(/\r\n?/g, '\n').replace(NORMALISE, (c) => (c.length === 1 ? ' ' : c));
}

/** Front matter is refused outright: after skipping whitespace/control/format chars, the text begins `---`. */
function startsFrontMatter(text) {
  let i = 0;
  while (i < text.length && FRONT_MATTER_SKIP.test(text[i])) i++;
  return text.startsWith('---', i);
}

/** Every double-quoted string in a click/link/links statement must be a safe URL or contain no ':'. */
function quotedStringsSafe(statement) {
  for (const m of statement.matchAll(QUOTED)) {
    const decoded = javaStrip(decodeEntities(m[1]));
    if (decoded.includes(':') && !safeUrl(decoded)) return false;
  }
  return true;
}

/** Backstop: after entity decoding and dropping whitespace/control chars, a script scheme anywhere refuses. */
function hasScriptScheme(source) {
  const u = decodeEntities(source).replace(CONTROL_OR_SPACE, '').toLowerCase();
  return u.includes('javascript:') || u.includes('vbscript:');
}

/** Splits a line on ';' outside double quotes (Mermaid allows several statements per line). */
function splitStatements(line) {
  const out = [];
  let quoted = false;
  let start = 0;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (c === '"') {
      quoted = !quoted;
    } else if (c === ';' && !quoted) {
      out.push(line.slice(start, i));
      start = i + 1;
    }
  }
  out.push(line.slice(start));
  return out;
}

function decodeEntities(s) {
  return s.replace(ENTITY, (m, hex, dec, named, bare) => {
    if (hex !== undefined) return codePoint(parseInt(hex, 16), m);
    if (dec !== undefined) return codePoint(parseInt(dec, 10), m);
    if (named !== undefined) return NAMED_ENTITIES[named];
    return codePoint(parseInt(bare, 10), m);
  });
}

/** Java leaves an entity undecoded when it overflows an int or is not a valid code point. */
function codePoint(cp, fallback) {
  return cp <= 0x10ffff ? String.fromCodePoint(cp) : fallback;
}

/** Applies the scheme rule after removing whitespace/control chars browsers ignore inside a scheme. */
function safeUrl(url) {
  const u = url.replace(URL_IGNORED, '').toLowerCase();
  if (u.startsWith('/') || u.startsWith('#') || u.startsWith('http://') || u.startsWith('https://')) return true;
  const colon = u.indexOf(':');
  if (colon < 0) return true;
  const slash = u.indexOf('/');
  return slash >= 0 && slash < colon;
}

function pageName(url) {
  if (!url.startsWith('/wiki/')) return null;
  let name = url.slice('/wiki/'.length);
  const cut = firstIndexOf(name, '#', '?');
  if (cut >= 0) name = name.slice(0, cut);
  try {
    name = javaUrlDecode(name.replaceAll('+', '%2B'));
  } catch (e) {
    console.warn(`[fences] Mermaid link target '${url}' has bad percent-encoding; not tracked:`, e.message);
    return null;
  }
  return isJavaBlank(name) ? null : name;
}

function firstIndexOf(s, a, b) {
  const i = s.indexOf(a);
  const j = s.indexOf(b);
  return i < 0 ? j : j < 0 ? i : Math.min(i, j);
}

/** java.lang.Character.isWhitespace for a UTF-16 code unit / BMP code point. */
function isJavaWhitespace(c) {
  return (c >= 0x09 && c <= 0x0d) || (c >= 0x1c && c <= 0x20) || c === 0x1680
    || (c >= 0x2000 && c <= 0x2006) || (c >= 0x2008 && c <= 0x200a)
    || c === 0x2028 || c === 0x2029 || c === 0x205f || c === 0x3000;
}

/** java.lang.String.strip(), with loops so a long interior whitespace run can never go quadratic. */
function javaStrip(s) {
  let start = 0;
  let end = s.length;
  while (start < end && isJavaWhitespace(s.charCodeAt(start))) start++;
  while (end > start && isJavaWhitespace(s.charCodeAt(end - 1))) end--;
  return start === 0 && end === s.length ? s : s.slice(start, end);
}

function isJavaBlank(s) {
  return javaStrip(s) === '';
}

/** HexFormat.fromHexDigits(s, i, i + 2), as java.net.URLDecoder uses it: exactly two ASCII hex digits. */
function hexByte(s, i) {
  const pair = s.slice(i, i + 2);
  if (!/^[0-9a-fA-F]{2}$/.test(pair)) throw new Error(`Illegal hex characters in escape (%) pattern: ${pair}`);
  return parseInt(pair, 16);
}

const REPL = 0xfffd;
const isCont = (b) => (b & 0xc0) === 0x80;

/**
 * new String(bytes, UTF_8) as JDK 25 does it (java.lang.String.decodeUTF8_UTF16). Its replacement differs from
 * TextDecoder's WHATWG rule: e.g. an encoded surrogate (ED A0 80) is ONE U+FFFD, not three; a truncated
 * sequence at the very end is one U+FFFD; a leading BOM is kept.
 */
function javaUtf8Decode(src) {
  const out = [];
  const sl = src.length;
  let sp = 0;
  while (sp < sl) {
    const b1 = src[sp++];
    if (b1 < 0x80) {
      out.push(b1);
    } else if (b1 >= 0xc2 && b1 <= 0xdf) {
      if (sp < sl) {
        const b2 = src[sp++];
        if (!isCont(b2)) {
          out.push(REPL);
          sp--;
        } else {
          out.push(((b1 & 0x1f) << 6) | (b2 & 0x3f));
        }
        continue;
      }
      out.push(REPL);
      break;
    } else if (b1 >= 0xe0 && b1 <= 0xef) {
      if (sp + 1 < sl) {
        const b2 = src[sp++];
        const b3 = src[sp++];
        if ((b1 === 0xe0 && (b2 & 0xe0) === 0x80) || !isCont(b2) || !isCont(b3)) {
          out.push(REPL);
          sp -= 3;
          sp += (b1 === 0xe0 && (b2 & 0xe0) === 0x80) || !isCont(b2) ? 1 : 2;
        } else {
          const c = ((b1 & 0x0f) << 12) | ((b2 & 0x3f) << 6) | (b3 & 0x3f);
          out.push(c >= 0xd800 && c <= 0xdfff ? REPL : c);
        }
        continue;
      }
      if (sp < sl && ((b1 === 0xe0 && (src[sp] & 0xe0) === 0x80) || !isCont(src[sp]))) {
        out.push(REPL);
        continue;
      }
      out.push(REPL);
      break;
    } else if (b1 >= 0xf0 && b1 <= 0xf7) {
      if (sp + 2 < sl) {
        const b2 = src[sp++];
        const b3 = src[sp++];
        const b4 = src[sp++];
        const uc = ((b1 & 0x07) << 18) | ((b2 & 0x3f) << 12) | ((b3 & 0x3f) << 6) | (b4 & 0x3f);
        if (!isCont(b2) || !isCont(b3) || !isCont(b4) || uc < 0x10000 || uc > 0x10ffff) {
          out.push(REPL);
          sp -= 4;
          sp += malformed4(src, sp);
        } else {
          out.push(0xd800 + ((uc - 0x10000) >> 10), 0xdc00 + ((uc - 0x10000) & 0x3ff));
        }
        continue;
      }
      if (b1 > 0xf4 || (sp < sl && malformed4Lead(b1, src[sp]))) {
        out.push(REPL);
        continue;
      }
      sp++;
      out.push(REPL);
      if (sp < sl && !isCont(src[sp])) continue;
      break;
    } else {
      out.push(REPL);
    }
  }
  let str = '';
  for (let i = 0; i < out.length; i += 4096) str += String.fromCharCode(...out.slice(i, i + 4096));
  return str;
}

function malformed4Lead(b1, b2) {
  return (b1 === 0xf0 && (b2 < 0x90 || b2 > 0xbf)) || (b1 === 0xf4 && (b2 & 0xf0) !== 0x80) || !isCont(b2);
}

function malformed4(src, sp) {
  const b1 = src[sp];
  const b2 = src[sp + 1];
  if (b1 > 0xf4 || malformed4Lead(b1, b2)) return 1;
  return isCont(src[sp + 2]) ? 3 : 2;
}

/** java.net.URLDecoder.decode(s, UTF_8). Throws where Java throws IllegalArgumentException. */
function javaUrlDecode(s) {
  const n = s.length;
  let out = '';
  let i = 0;
  while (i < n) {
    let c = s[i];
    if (c === '+') {
      out += ' ';
      i++;
    } else if (c === '%') {
      const bytes = [];
      while (i + 2 < n && c === '%') {
        bytes.push(hexByte(s, i + 1));
        i += 3;
        if (i < n) c = s[i];
      }
      if (i < n && c === '%') throw new Error('Incomplete trailing escape (%) pattern');
      out += javaUtf8Decode(bytes);
    } else {
      out += c;
      i++;
    }
  }
  return out;
}
