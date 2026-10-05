# Page Aliases

Sometimes one page needs more than one name. For example, a page called `PageAlias` may also be known as "Redirect" or "Alternate names". Add the other names to the page's `aliases` frontmatter list:

```yaml
---
aliases:
  - Redirect
  - Alternate names
---
```

Each entry can be up to 100 characters, and blank entries are flagged. The **Frontmatter** tab in the editor has a field for it.

## What an alias does

* The quick switcher (Ctrl+K or Cmd+K) matches aliases as well as page names.
* A `[[Redirect]]` link resolves to the page when no page has that exact name. See [WikiName](WikiName) for the order.
* The editor's unlinked-mention scan matches an alias in your text and offers to link it.

An alias does not redirect the address bar: `/wiki/Redirect` still looks for a page called `Redirect`. For the field's rules, see [Frontmatter](https://github.com/jakefearsd/wikantik/blob/main/docs/user/Frontmatter.md).
