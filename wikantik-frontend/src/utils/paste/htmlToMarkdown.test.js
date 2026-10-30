import { describe, it, expect } from 'vitest';
import { htmlToMarkdown, wikiPageTarget } from './htmlToMarkdown';
import googleDocs from './__fixtures__/google-docs.html?raw';
import word from './__fixtures__/word.html?raw';
import githubReadme from './__fixtures__/github-readme.html?raw';
import confluence from './__fixtures__/confluence.html?raw';
import webArticle from './__fixtures__/web-article.html?raw';
import vscode from './__fixtures__/vscode.html?raw';
import wikantikView from './__fixtures__/wikantik-view.html?raw';

const md = (html, opts) => htmlToMarkdown(html, opts)?.markdown;

describe('htmlToMarkdown — real clipboard sources', () => {
  it('Google Docs: styled spans become emphasis, nested lists nest, the guid wrapper is not bold', () => {
    const result = htmlToMarkdown(googleDocs);
    expect(result.markdown).toBe([
      '# Quarterly Plan',
      '',
      'We will ship **three** *major* features, run `make release`~~twice~~ and see [the roadmap](https://example.com/roadmap).',
      '',
      '## Milestones',
      '',
      '- Design review',
      '  - Security sign-off',
      '- Launch',
      '',
      '1. Draft',
      '2. Publish',
      '',
      '| Owner | Due |',
      '| --- | --- |',
      '| Platform \\| Infra | March (tentative) |',
    ].join('\n'));
    expect(result.droppedImages).toBe(1); // the inline data: image cannot be pasted as a link
  });

  it('Word: mso-list paragraphs become real (nested) lists, o:p and conditional comments vanish', () => {
    expect(md(word)).toBe([
      '## Incident Runbook',
      '',
      'Restart the **ingest** worker, then *verify* the queue drains.',
      '',
      '- Check the dashboard',
      '  - Queue depth',
      '- Page the owner',
      '',
      '1. Drain',
      '2. Restart',
      '',
      '| Host | Port |',
      '| --- | --- |',
      '| docker1 | 8080 |',
      '',
      'See [the ops guide](https://example.com/ops).',
    ].join('\n'));
  });

  it('GitHub README: language-tagged fence, task list, table, blockquote; copy buttons and permalinks dropped', () => {
    expect(md(githubReadme)).toBe([
      '## Installation',
      '',
      'Install with `npm` and read the [guide](https://github.com/acme/widget/blob/main/docs/guide.md):',
      '',
      '```shell',
      'npm install --save widget',
      'widget init',
      '```',
      '',
      '- [x] Configure the client',
      '- [ ] Add **retries**',
      '',
      '| Option | Default |',
      '| --- | --- |',
      '| `timeout` | 30s |',
      '',
      '> Note: ~~v1~~ v2 only.',
    ].join('\n'));
  });

  it('Confluence: list items keep their nested list tight, brush: language, colspan padded, hr', () => {
    expect(md(confluence)).toBe([
      '## Deploy Steps',
      '',
      'Follow these in order. Ask in [Escalation](https://acme.atlassian.net/wiki/spaces/OPS/pages/123/Escalation) if blocked.',
      '',
      '1. Tag the release',
      '   1. Use `vX.Y.Z`',
      '2. Run the pipeline',
      '',
      '```bash',
      './deploy.sh --env prod',
      'echo "done"',
      '```',
      '',
      '| Env | URL |',
      '| --- | --- |',
      '| prod | <https://wiki.example.com> |',
      '| All hosts share one database. |  |',
      '',
      '---',
      '',
      'Last reviewed by **Ops**.',
    ].join('\n'));
  });

  it('web article: escapes markdown and $ in prose, drops citations, edit links, scripts, buttons and javascript: links', () => {
    expect(md(webArticle)).toBe([
      'An **interval tree** is a [tree data structure](https://en.wikipedia.org/wiki/Tree_data_structure) to hold *intervals*. It costs \\$5 to learn and uses \\* and \\_ characters.',
      '',
      '### Naive approach',
      '',
      'In a simple case, **intervals** do not overlap.',
      '',
      '![Example tree](https://upload.wikimedia.org/tree.png)',
      '',
      'Show more and [paren link](https://example.com/a\\(b\\)c).',
    ].join('\n'));
  });

  it('VS Code: colour-only spans carry no structure, so the caller pastes the plain text', () => {
    expect(htmlToMarkdown(vscode)).toBeNull();
  });

  it('this wiki: page links become wiki links, KaTeX becomes $…$ / $$…$$, heading anchors drop, fence outgrows inner backticks', () => {
    expect(md(wikantikView, { origin: 'http://localhost:8080' })).toBe([
      '## Usage',
      '',
      'Read [Hybrid Retrieval](HybridRetrieval) and the [phases](ClusterDeclarationDesign#phases) first. The cost is $O(\\log n)$ per query.',
      '',
      '$$',
      'E = mc^2',
      '$$',
      '',
      '````java',
      'int x = 1; // `ticks` ``` inside',
      '````',
    ].join('\n'));
  });

  it('this wiki from another origin: links stay absolute', () => {
    expect(md(wikantikView, { origin: 'https://wiki.wikantik.com' }))
      .toContain('[Hybrid Retrieval](http://localhost:8080/wiki/HybridRetrieval)');
  });
});

describe('htmlToMarkdown — rules', () => {
  it('returns null for empty or structure-free HTML', () => {
    expect(htmlToMarkdown('')).toBeNull();
    expect(htmlToMarkdown(null)).toBeNull();
    expect(htmlToMarkdown('<p>just words</p><p>more words</p>')).toBeNull();
    expect(htmlToMarkdown('<span style="font-weight:400">plain</span>')).toBeNull();
  });

  it('an inline fragment stays inline (no surrounding blank lines) and says so', () => {
    expect(htmlToMarkdown('<span>some </span><b>bold</b><span> text</span>')).toEqual({ markdown: 'some **bold** text', droppedImages: 0, inline: true });
    expect(htmlToMarkdown('<h2>Title</h2>').inline).toBe(false);
  });

  it('a lone link keeps its target', () => {
    expect(md('<a href="https://example.com/x">Example</a>')).toBe('[Example](https://example.com/x)');
  });

  it('a link whose text is its URL becomes an autolink', () => {
    expect(md('<a href="https://example.com/">https://example.com/</a>')).toBe('<https://example.com/>');
  });

  it('flanking whitespace moves outside emphasis', () => {
    expect(md('<p>a<strong> b </strong>c</p>')).toBe('a **b** c');
  });

  it('nested bold collapses to one strong', () => {
    expect(md('<p><b>outer <strong>inner</strong></b></p>')).toBe('**outer inner**');
  });

  it('bold inside headings and header cells is dropped', () => {
    expect(md('<h3><b>Title</b></h3>')).toBe('### Title');
    expect(md('<table><tr><th><b>A</b></th></tr><tr><td>1</td></tr></table>')).toBe('| A |\n| --- |\n| 1 |');
  });

  it('formatting inside code is dropped', () => {
    expect(md('<p>run <code><b>ls</b></code></p>')).toBe('run `ls`');
  });

  it('line breaks inside a paragraph become CommonMark backslash breaks; stray block-level breaks vanish', () => {
    expect(md('<p><b>Line one</b><br>Line two<br></p><br><p><i>Next</i></p>')).toBe('**Line one**\\\nLine two\n\n*Next*');
  });

  it('ordered lists honour start', () => {
    expect(md('<ol start="4"><li>four</li><li>five</li></ol>')).toBe('4. four\n5. five');
  });

  it('list items with several paragraphs stay loose and indented', () => {
    expect(md('<ul><li><p>one</p><p>more</p></li><li>two</li></ul>')).toBe('- one\n\n  more\n\n- two');
  });

  it('images: http(s) kept, data:/blob:/file: dropped and counted', () => {
    const result = htmlToMarkdown('<p><img src="https://x.test/a.png" alt="A"><img src="blob:https://x.test/1"><img src="file:///C:/a.png"></p>');
    expect(result).toEqual({ markdown: '![A](https://x.test/a.png)', droppedImages: 2, inline: false });
  });

  it('a page of only dropped images converts to nothing (caller falls back to plain text)', () => {
    expect(htmlToMarkdown('<img src="data:image/png;base64,AAAA">')).toBeNull();
  });

  it('a single-cell layout table yields its content', () => {
    expect(md('<table><tr><td><p><b>Boxed</b> note</p></td></tr></table>')).toBe('**Boxed** note');
  });

  it('a table whose cells hold block structure is laid out as blocks, not a broken pipe table', () => {
    expect(md('<table><tr><td><h2>Left</h2></td><td><ul><li>a</li><li>b</li></ul></td></tr></table>'))
      .toBe('## Left\n\n- a\n- b');
  });

  it('pipe tables pad ragged rows', () => {
    expect(md('<table><tr><th>a</th><th>b</th><th>c</th></tr><tr><td>1</td></tr></table>'))
      .toBe('| a | b | c |\n| --- | --- | --- |\n| 1 |  |  |');
  });

  it('pre without a language gets a bare fence; language-* class on code is read', () => {
    expect(md('<pre>plain\ncode</pre>')).toBe('```\nplain\ncode\n```');
    expect(md('<pre><code class="language-python">print(1)\n</code></pre>')).toBe('```python\nprint(1)\n```');
  });

  it('non-breaking spaces become plain spaces', () => {
    expect(md('<p><b>a</b>\u00a0b</p>')).toBe('**a** b');
  });

  it('a link with no text is dropped; a link wrapping only an image keeps the image', () => {
    expect(md('<p><i>x</i><a href="https://e.test"></a></p>')).toBe('*x*');
    expect(md('<a href="https://e.test"><img src="https://e.test/i.png" alt="i"></a>')).toBe('[![i](https://e.test/i.png)](https://e.test)');
  });
});

describe('htmlToMarkdown — review fixes', () => {
  it('blank lines and trailing spaces inside a code fence survive the output cleanup', () => {
    expect(md('<pre><code class="language-python">def a():\n    pass  \n\n\ndef b(): pass</code></pre>'))
      .toBe('```python\ndef a():\n    pass  \n\n\ndef b(): pass\n```');
  });

  it('cleanup outside fences still collapses blank lines after a fence', () => {
    expect(md('<pre>x</pre><p><b>a</b></p>')).toBe('```\nx\n```\n\n**a**');
  });

  it('<br> inside <pre> is a newline (Confluence / legacy highlighters)', () => {
    expect(md('<pre><span>line one</span><br><span>line two</span></pre>')).toBe('```\nline one\nline two\n```');
  });

  it('a dropped image inside a table is counted once', () => {
    expect(htmlToMarkdown('<table><tr><th>a</th><th>b</th></tr><tr><td><img src="data:image/png;base64,AAA"></td><td>x</td></tr></table>').droppedImages).toBe(1);
    expect(htmlToMarkdown('<table><tr><td><h2>H</h2><img src="data:image/png;base64,AAA"></td></tr></table>').droppedImages).toBe(1);
  });

  it('prose that looks like an HTML tag is escaped, a lone < is not', () => {
    expect(md('<p>Use the <b>&lt;div&gt;</b> element, a &lt; b, and &lt;/p&gt;</p>'))
      .toBe('Use the **\\<div>** element, a < b, and \\</p>');
  });

  it('only scheme-qualified hrefs become autolinks', () => {
    expect(md('<a href="www.x.com">www.x.com</a>')).toBe('[www.x.com](www.x.com)');
    expect(md('<a href="mailto:a@b.test">mailto:a@b.test</a>')).toBe('<mailto:a@b.test>');
  });
});

describe('wikiPageTarget', () => {
  const origin = 'https://wiki.wikantik.com';
  it('maps same-origin /wiki/ URLs to page names, keeping the fragment', () => {
    expect(wikiPageTarget('https://wiki.wikantik.com/wiki/Main', origin)).toBe('Main');
    expect(wikiPageTarget('https://wiki.wikantik.com/wiki/Main#intro', origin)).toBe('Main#intro');
    expect(wikiPageTarget('/wiki/Main', origin)).toBe('Main');
  });

  it('leaves other URLs alone', () => {
    expect(wikiPageTarget('https://other.test/wiki/Main', origin)).toBeNull();
    expect(wikiPageTarget('https://wiki.wikantik.com/wiki/Main?format=md', origin)).toBeNull();
    expect(wikiPageTarget('https://wiki.wikantik.com/edit/Main', origin)).toBeNull();
    expect(wikiPageTarget('https://wiki.wikantik.com/wiki/Has%20Space', origin)).toBeNull();
    expect(wikiPageTarget('https://wiki.wikantik.com/wiki/Main', '')).toBeNull();
    expect(wikiPageTarget('::not a url', origin)).toBeNull();
  });
});
