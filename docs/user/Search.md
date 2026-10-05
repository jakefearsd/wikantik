# Finding Pages: Switcher, Commands and Search

This guide is for people who read and edit the wiki. It covers the quick switcher and command palette that jump to a page from the keyboard, the full-text search page with its filters, and what the search box does and does not understand. In every shortcut, **Mod** means Ctrl on Windows and Linux and Cmd on a Mac.

## Jump to a page or run a command

The quick switcher is a pop-up with one text box. Open it from anywhere in the wiki:

| Keys | Opens |
|------|-------|
| Mod+K | The switcher in page mode. Inside the page editor, Mod+K inserts a link instead; use Mod+O there. |
| Mod+O | The switcher in page mode, also inside the editor ("Go to page" in the command list). |
| Mod+P | The switcher in command mode, with `>` already typed. |
| Mod+Alt+N | Today's daily note (opens it, or starts it as a new page named like `2026-10-05`). |

You can also click **Search…** in the sidebar, which opens the switcher in page mode. Press **Esc** or click outside to close it.

### Find a page

Type any part of a page's name or title, or its initials: `bjp` finds `BackgroundJobProcessing`. Matching ignores case and spaces, and a page's frontmatter `aliases` count as names. Results arrive after about 0.1 seconds (page names) and 0.2 seconds (full-text matches), in these groups:

- **Pages** — up to 8 pages whose name, title or alias matches.
- **Full-text matches** — up to 8 more pages whose text matches, not already listed above.
- **Search full text for "..."** — opens the full search page for what you typed.
- **Create page "..."** — starts a new page with that title. It is hidden when a page with that exact name exists. See [Editing.md](Editing.md#create-a-page).

With an empty box the switcher shows **Recent**: the pages you viewed most recently when you are signed in, or the latest page changes when you are not.

| Key in the switcher | Does |
|---------------------|------|
| Up / Down | Move the highlight |
| Enter | Open the highlighted page, run the command, or start the search or create action |
| Ctrl+Enter or Cmd+Enter | Open the highlighted page in a new browser tab (pages only) |
| Esc | Close |

### Run a command

Start the box with `>` (or press Mod+P) to list commands instead of pages. Type to filter them; the same fuzzy matching applies. Each row shows its shortcut when it has one.

Commands available everywhere:

| Command | Shortcut |
|---------|----------|
| Go to page | Mod+O |
| Search full text | none |
| Recent changes | none |
| Open today's daily note | Mod+Alt+N |
| Toggle sidebar | none |
| New page | none |

On a page, **Edit this page** and **Page history** are added. On the edit screen the editor commands appear (Save, Bold, Italic, Insert link, headings, callouts, table, code block, math block, rule, image, fold or unfold headings, toggle preview, toggle rail, toggle live preview); their shortcuts are listed in [Editing.md](Editing.md#toolbar-and-keyboard-shortcuts). If you can create pages, **Import Obsidian vault…** is also listed.

## Search the full text

Open `/search?q=your+words` by choosing **Search full text for "..."** in the switcher, or by clicking a tag, a cluster name or an author name on a page or in a result. The search page shows the number of matches, then one card per page.

Each result card shows:

- The page name as a link, with a **↯** badge if the page was synced from an external source.
- The page summary, if it has one.
- Up to two snippets of matching text, rendered as Markdown.
- The author (click to search for that name), the last-modified date, the cluster (click to search for it) and a match score such as "87% match".
- The page's tags (click to search for one).

Words you searched for are highlighted in the title and summary. The server returns at most 20 pages for one search, so refine your words if the page you want is missing; the **Load more** button reveals results 20 at a time but cannot go past what the server returned.

### Narrow the results with filters

When a search returns more than one page, a **Filters** panel appears beside the results. It is built from the results already on screen, so it adds no delay.

| Filter | Choices |
|--------|---------|
| Topic | The clusters of the matching pages, with counts |
| Author | The authors of the matching pages, with counts |
| Tag | The tags of the matching pages, with counts |
| Modified | Any time, Past week, Past month, Past year |

Choices inside one filter widen the match (Topic A or Topic B). Different filters narrow it (Topic A and Tag X and Past month). The heading then reads "N results for ... (filtered from M)". **Clear filters** removes all of them. Changing the search words resets the filters. There is no filter for page type.

### What the search understands

Type plain words. The search page and the switcher both send your words to `GET /api/search?q=...`, and the server treats everything you type as ordinary text:

- Words are matched against each page's body, name, summary, tags, cluster, keywords, author and attachments. A match in the page name counts most, then keywords, then the summary, then tags and cluster, then the body.
- Operators do not work. The server escapes `+ - ! ( ) { } [ ] ^ " ~ * ? : \ /` and doubled `&&` or `||`, and lowercases `AND`, `OR` and `NOT`, so `wei*rd` searches for the literal text, quotes do not make an exact phrase, and `tags:security` is not a field search.
- When hybrid search is on (`wikantik.search.hybrid.enabled`, default `true`), the keyword results are re-ranked with semantic (embedding) similarity, so a page can match a question even when it does not use your exact words. If the embedding service is down, you get keyword results only.
- Pages you are not allowed to view never appear.

Programmatic callers can add `raw=true` to `/api/search` to skip the escaping and send Lucene query syntax; a malformed query then returns HTTP 400 ("Invalid search query"). The web interface never sends it. Every other detail of that syntax is Lucene's, not the wiki's.

## Source files

- `wikantik-frontend/src/hooks/useGlobalHotkeys.js` — Mod+K, Mod+O, Mod+P, Mod+Alt+N
- `wikantik-frontend/src/components/QuickOverlay.jsx` — the switcher and command palette
- `wikantik-frontend/src/commands/useGlobalCommands.js` — the global command list
- `wikantik-frontend/src/utils/fuzzy.js` — fuzzy matching
- `wikantik-frontend/src/components/SearchResultsPage.jsx`, `SearchFacets.jsx`, `utils/searchFacets.js`
- `wikantik-rest/src/main/java/com/wikantik/rest/SearchResource.java` — query escaping and `raw`
- `wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java` — keyword plus hybrid re-rank
- `wikantik-main/src/main/java/com/wikantik/search/subsystem/lucene/DefaultLuceneSearcher.java` — searched fields and boosts
