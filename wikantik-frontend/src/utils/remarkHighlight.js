/**
 * Obsidian `==highlight==` for the editor preview: a micromark construct shaped exactly like GFM strikethrough
 * (`micromark-extension-gfm-strikethrough`, with `=` for `~`), so the flanking rules and nesting are the same
 * emphasis machinery the server's delimiter processor uses (`HighlightDelimiterProcessor`): `==**x**==` nests,
 * `a == b` and `== x ==` are text, and only a run of EXACTLY two `=` on both sides pairs (`===x===` is text).
 * A text-node regex would break on nested marks, so this is a real syntax extension. The mdast node is
 * `highlight`, rendered as `<mark>`. The parity fixture `__fixtures__/highlight.json` is shared with the server
 * (`HighlightParityTest`).
 *
 * The three helpers below are the few lines this needs from micromark-util-chunked / -classify-character /
 * -resolve-all, inlined so the build does not depend on micromark's internal packages.
 */
const EQUALS = 61;
const markdownLineEndingOrSpace = (c) => c !== null && (c < 0 || c === 32);
const PUNCTUATION = /\p{P}|\p{S}/u;
const WHITESPACE = /\p{Zs}|[\t\n\f\r]/u;

/** micromark's character classes: 1 whitespace (or the start/end of input), 2 punctuation, undefined otherwise. */
function classify(code) {
  if (code === null || markdownLineEndingOrSpace(code)) return 1;
  const ch = String.fromCodePoint(code);
  if (WHITESPACE.test(ch)) return 1;
  if (PUNCTUATION.test(ch)) return 2;
  return undefined;
}

/** Run every distinct `resolveAll` of `constructs` over `events`, in order. */
function resolveEach(constructs, events, context) {
  const called = [];
  let out = events;
  for (const construct of constructs) {
    const resolve = construct.resolveAll;
    if (resolve && !called.includes(resolve)) {
      out = resolve(out, context);
      called.push(resolve);
    }
  }
  return out;
}

const sizeOf = (token) => token.end.offset - token.start.offset;

function resolveAllHighlight(events, context) {
  for (let index = 0; index < events.length; index += 1) {
    // Find a token that can close.
    if (events[index][0] === 'enter' && events[index][1].type === 'highlightSequenceTemporary' && events[index][1]._close) {
      // Now walk back to find an opener.
      for (let open = index - 1; open >= 0; open -= 1) {
        if (events[open][0] === 'exit' && events[open][1].type === 'highlightSequenceTemporary' && events[open][1]._open
          && sizeOf(events[index][1]) === sizeOf(events[open][1])) {
          events[index][1].type = 'highlightSequence';
          events[open][1].type = 'highlightSequence';
          const highlight = { type: 'highlight', start: { ...events[open][1].start }, end: { ...events[index][1].end } };
          const text = { type: 'highlightText', start: { ...events[open][1].end }, end: { ...events[index][1].start } };
          const next = [['enter', highlight, context], ['enter', events[open][1], context], ['exit', events[open][1], context], ['enter', text, context]];
          const insideSpan = context.parser.constructs.insideSpan.null;
          if (insideSpan) next.push(...resolveEach(insideSpan, events.slice(open + 1, index), context));
          next.push(['exit', text, context], ['enter', events[index][1], context], ['exit', events[index][1], context], ['exit', highlight, context]);
          events.splice(open - 1, index - open + 3, ...next);
          index = open + next.length - 2;
          break;
        }
      }
    }
  }
  // Delimiter runs that never paired are plain text.
  for (const event of events) {
    if (event[1].type === 'highlightSequenceTemporary') event[1].type = 'data';
  }
  return events;
}

function tokenizeHighlight(effects, ok, nok) {
  const { previous, events } = this;
  let size = 0;
  return start;

  function start(code) {
    if (previous === EQUALS && events[events.length - 1][1].type !== 'characterEscape') return nok(code);
    effects.enter('highlightSequenceTemporary');
    return more(code);
  }

  function more(code) {
    const before = classify(previous);
    if (code === EQUALS) {
      if (size > 1) return nok(code); // a third `=`: not a highlight delimiter
      effects.consume(code);
      size += 1;
      return more;
    }
    if (size < 2) return nok(code); // a lone `=`
    const token = effects.exit('highlightSequenceTemporary');
    const after = classify(code);
    token._open = !after || (after === 2 && Boolean(before));
    token._close = !before || (before === 2 && Boolean(after));
    return ok(code);
  }
}

const construct = { name: 'highlight', tokenize: tokenizeHighlight, resolveAll: resolveAllHighlight };

const syntax = {
  text: { [EQUALS]: construct },
  insideSpan: { null: [construct] },
  attentionMarkers: { null: [EQUALS] },
};

const fromMarkdown = {
  canContainEols: ['highlight'],
  enter: {
    highlight(token) {
      this.enter({ type: 'highlight', children: [], data: { hName: 'mark' } }, token);
    },
  },
  exit: {
    highlight(token) {
      this.exit(token);
    },
  },
};

export default function remarkHighlight() {
  const data = this.data();
  (data.micromarkExtensions || (data.micromarkExtensions = [])).push(syntax);
  (data.fromMarkdownExtensions || (data.fromMarkdownExtensions = [])).push(fromMarkdown);
}
