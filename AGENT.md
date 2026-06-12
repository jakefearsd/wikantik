
## Math Equation Rules
Wikantik uses KaTeX for math rendering. Follow these rules to ensure equations render correctly:
1. **Block Math**: ALWAYS use `$$` with blank lines before and after. DO NOT wrap block math in `<div>` or `<center>` tags, as this bypasses the markdown parser's `math-display` class assignment, causing KaTeX to ignore it. The markdown parser natively handles the `$$` blocks correctly and assigns the required `.math-display` class.
    ```markdown
    
    $$
    E = mc^2
    $$
    
    ```
2. **Inline Math**: ALWAYS use `$math$` with NO SPACES inside the boundary dollars (e.g. `$x$` not `$ x $`). Spaces prevent it from being parsed as inline math and fallback to text, where HTML escaping will break it.

## Frontmatter Rules
Wikantik stores frontmatter metadata independently of the content body.
1. DO NOT include the YAML `---` frontmatter block in the `content` string when updating or publishing pages.
2. If `---` is included in the `content`, Wikantik's markdown renderer will literally render it as an `<hr>` and a bulleted list at the top of the page.
