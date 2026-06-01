// CodeMirror 6 autocomplete source for `[[`-triggered internal wiki links.
// Typing `[[` (optionally followed by a fragment) offers matching page names;
// accepting one replaces the `[[fragment` with a `[Name](Name)` markdown link,
// matching this wiki's internal-link convention (bare page name as the target).

const MAX_OPTIONS = 20;

// `[[` followed by any run of chars that are not a closing bracket or newline,
// anchored to the cursor. This is intentionally permissive on spaces so page
// titles with spaces still match.
const TRIGGER = /\[\[[^\]\n]*$/;

/**
 * @param {() => string[]} getPageNames - returns the current page-name list
 *   (a getter so the source always sees freshly-loaded names).
 * @returns {(context) => ({from: number, options: object[]} | null)}
 */
export function createWikiLinkSource(getPageNames) {
  return (context) => {
    const match = context.matchBefore(TRIGGER);
    if (!match) return null;

    const fragment = match.text.slice(2).toLowerCase(); // drop the leading [[
    const names = getPageNames() || [];
    const options = names
      .filter(name => name.toLowerCase().includes(fragment))
      .slice(0, MAX_OPTIONS)
      .map(name => ({
        label: name,
        type: 'wikilink',
        apply: `[${name}](${name})`,
      }));

    if (options.length === 0) return null;
    return { from: match.from, options };
  };
}
