import {
  toggleWrap, toggleLinePrefix, insertLink, insertTable, insertCodeBlock, setHeading, insertCallout,
  insertMathBlock, insertRule, insertFence, insertFootnote, insertEmbed, insertAccessRule,
} from './markdownFormat';

/** The Obsidian-syntax wrap markers: the toolbar, the palette and the editor keymap all toggle these. */
export const MARKERS = { highlight: '==', strike: '~~', comment: '%%' };

/**
 * What a new diagram fence starts with, by fence language. Mermaid's is the first source the shared reader fixture
 * (`__fixtures__/renderedFences.json`, `mermaidPolicy`) allows; that fixture holds no PlantUML source, so PlantUML's
 * is the smallest whole diagram in the `@startuml` spelling the wiki's pages use.
 */
export const FENCE_STARTERS = {
  mermaid: 'flowchart TD\n  A-->B',
  plantuml: '@startuml\nA -> B\n@enduml',
};

/**
 * Every format command as a pure edit: `(state, ctx) => edit`, where `state` is `{ text, selStart, selEnd }`, `ctx` is
 * `{ liveActive, arg }` and the edit is `{ text, selStart, selEnd }` (plus `changes` for far-apart insertions).
 * Only `codeblock` (reads `liveActive`) and `accessrule` (takes `arg`) use the context.
 */
export const FORMAT_COMMANDS = {
  bold: (state) => toggleWrap(state, '**'),
  italic: (state) => toggleWrap(state, '*'),
  code: (state) => toggleWrap(state, '`'),
  highlight: (state) => toggleWrap(state, MARKERS.highlight),
  strike: (state) => toggleWrap(state, MARKERS.strike),
  comment: (state) => toggleWrap(state, MARKERS.comment),
  // Rich: the fences are hidden and the header's picker sets the language, so no placeholder; the caret goes to the body.
  codeblock: (state, { liveActive } = {}) => (liveActive
    ? insertFence(state, '', state.text.slice(state.selStart, state.selEnd))
    : insertCodeBlock(state)),
  heading: (state) => toggleLinePrefix(state, '## '),
  list: (state) => toggleLinePrefix(state, '- '),
  olist: (state) => toggleLinePrefix(state, '1. '),
  tasklist: (state) => toggleLinePrefix(state, '- [ ] '),
  quote: (state) => toggleLinePrefix(state, '> '),
  table: (state) => insertTable(state),
  link: (state) => insertLink(state),
  h1: (state) => setHeading(state, 1),
  h2: (state) => setHeading(state, 2),
  h3: (state) => setHeading(state, 3),
  h4: (state) => setHeading(state, 4),
  mathblock: (state) => insertMathBlock(state),
  rule: (state) => insertRule(state),
  footnote: (state) => insertFootnote(state),
  embed: (state) => insertEmbed(state),
  accessrule: (state, { arg } = {}) => insertAccessRule(state, arg), // AccessRuleForm validates what it hands over
};

/** The command for an id, or undefined: the table's own ids plus the `callout:<type>` and `fence:<language>` families. */
export function resolveFormatCommand(id) {
  if (id.startsWith('callout:')) {
    const type = id.slice('callout:'.length);
    return (state) => insertCallout(state, type);
  }
  if (id.startsWith('fence:')) {
    const language = id.slice('fence:'.length);
    return (state) => insertFence(state, language, FENCE_STARTERS[language] ?? '');
  }
  return Object.hasOwn(FORMAT_COMMANDS, id) ? FORMAT_COMMANDS[id] : undefined;
}
