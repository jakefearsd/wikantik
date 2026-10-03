/**
 * Inline math for the editor preview, following the same rule as `inlineMath.js` (Pandoc tex_math_dollars):
 * `$5 and $10` is currency, `$x$` is math. Use AFTER `[remarkMath, { singleDollarTextMath: false }]`: remark-math
 * still supplies display math, `$$..$$` text math and the mdast handlers; this plugin adds the single-`$` construct and
 * emits the same token types (`mathText`, `mathTextSequence`, `mathTextData`) so those handlers build `inlineMath`
 * nodes that rehype-katex renders unchanged.
 */
const DOLLAR = 36;
const BACKSLASH = 92;
// micromark char codes: -5 CR, -4 LF, -3 CRLF; -2 tab, -1 virtual space, 32 space.
const markdownLineEnding = (c) => c !== null && c < -2;
const markdownLineEndingOrSpace = (c) => c !== null && (c < 0 || c === 32);
const isDigit = (c) => c !== null && c >= 48 && c <= 57;

/** Refuse a `$` that follows another `$` (that is `$$`), unless it follows an escape or a just-closed inline math. */
function previous(code) {
  if (code !== DOLLAR) return true;
  const last = this.events[this.events.length - 1];
  return !!last && (last[1].type === 'characterEscape' || last[1].type === 'mathText');
}

function tokenize(effects, ok, nok) {
  let newlines = 0;
  let prevWs = false;
  return start;

  function start(code) {
    effects.enter('mathText');
    effects.enter('mathTextSequence');
    effects.consume(code);
    effects.exit('mathTextSequence');
    return afterOpen;
  }

  function afterOpen(code) {
    if (code === null || code === DOLLAR || markdownLineEndingOrSpace(code)) return nok(code);
    effects.enter('mathTextData');
    return content(code);
  }

  function content(code) {
    if (code === null) return nok(code);
    if (code === BACKSLASH) {
      effects.consume(code);
      newlines = 0;
      prevWs = false;
      return escaped;
    }
    if (code === DOLLAR) {
      if (prevWs) return nok(code);
      effects.exit('mathTextData');
      effects.enter('mathTextSequence');
      effects.consume(code);
      return afterClose;
    }
    if (markdownLineEnding(code)) {
      newlines += 1;
      if (newlines > 1) return nok(code);
      prevWs = true;
    } else if (markdownLineEndingOrSpace(code)) {
      prevWs = true;
    } else {
      newlines = 0;
      prevWs = false;
    }
    effects.consume(code);
    return content;
  }

  function escaped(code) {
    if (code === null) return nok(code);
    effects.consume(code);
    return content;
  }

  function afterClose(code) {
    if (isDigit(code)) return nok(code);
    effects.exit('mathTextSequence');
    effects.exit('mathText');
    return ok(code);
  }
}

const construct = { name: 'inlineDollarMath', tokenize, previous };

export default function remarkInlineMath() {
  const data = this.data();
  const extensions = data.micromarkExtensions || (data.micromarkExtensions = []);
  extensions.push({ text: { [DOLLAR]: construct } });
}
