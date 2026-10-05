# Search Page Help

There are two ways to find pages: the quick switcher for jumping to a page by name, and full-text search. For the full guide, see [Finding Pages](https://github.com/jakefearsd/wikantik/blob/main/docs/user/Search.md). **Mod** means Ctrl on Windows and Linux and Cmd on a Mac.

## Quick switcher

| Keys | Opens |
|------|-------|
| Mod+K or Mod+O | the switcher, to find a page |
| Mod+P | the switcher with a list of commands |
| Mod+Alt+N | today's daily note |

Type part of a page name, title or alias, or its initials, and press Enter to open the highlighted page. Ctrl+Enter or Cmd+Enter opens it in a new tab. The switcher also offers **Search full text for...** and, when no page has that name, **Create page...**.

## Full-text search

Choose **Search full text for...** or open `/search?q=your+words`. Each result shows the page summary, matching snippets, author, date, cluster and tags.

* Type plain words. Operators such as `+`, `-`, `AND`, quotes and `field:` prefixes are not interpreted; they are searched as ordinary text.
* Matches in the page name count most, then summary, tags and cluster, then the body.
* When semantic search is enabled, a page can match on meaning even if it does not use your exact words.
* You only see pages you are allowed to view. At most 20 pages come back, so refine your words if the page you want is missing.

## Filters

When a search returns more than one page, a **Filters** panel narrows the results by topic (cluster), author, tag and modified date. Choices inside one filter widen the match; different filters narrow it. **Clear filters** removes them.
