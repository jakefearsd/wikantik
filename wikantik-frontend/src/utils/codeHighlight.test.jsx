import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import ReactMarkdown from 'react-markdown';
import { createLowlight, common } from 'lowlight';
import { codeLanguage, rehypeHighlightCode, highlightCodeBlocks } from './codeHighlight';

const lowlight = createLowlight(common);

describe('codeLanguage', () => {
  it.each([
    ['language-js', 'js'],
    [['language-Java'], 'java'],
    ['foo language-c++ bar', 'c++'],
    ['', null],
    [undefined, null],
  ])('%s → %s', (cls, lang) => expect(codeLanguage(cls)).toBe(lang));
});

describe('rehypeHighlightCode (preview)', () => {
  const render = (md, opts) => renderToStaticMarkup(
    <ReactMarkdown rehypePlugins={[[rehypeHighlightCode, opts]]}>{md}</ReactMarkdown>,
  );
  it('highlights fenced blocks that declare a known language', () => {
    const out = render('```js\nconst x = 1;\n```', { lowlight });
    expect(out).toContain('hljs-keyword');
    expect(out).toContain('class="language-js hljs"');
  });
  it('leaves undeclared or unknown languages and inline code untouched', () => {
    expect(render('```\nconst x = 1;\n```', { lowlight })).not.toContain('hljs');
    expect(render('```nosuchlang\nx\n```', { lowlight })).not.toContain('hljs');
    expect(render('`const x`', { lowlight })).not.toContain('hljs');
  });
  it('is a no-op until the highlighter has loaded', () => {
    expect(render('```js\nconst x = 1;\n```', {})).not.toContain('hljs');
  });
});

describe('highlightCodeBlocks (page view DOM pass)', () => {
  it('highlights server-rendered blocks once, preserving text', () => {
    const root = document.createElement('div');
    root.innerHTML = '<pre><code class="language-js">const x = 1;</code></pre><pre><code>plain</code></pre>';
    highlightCodeBlocks(root, lowlight);
    highlightCodeBlocks(root, lowlight); // idempotent
    const code = root.querySelector('code.language-js');
    expect(code.querySelectorAll('.hljs-keyword')).toHaveLength(1);
    expect(code.textContent).toBe('const x = 1;');
    expect(root.querySelectorAll('pre')[1].innerHTML).toBe('<code>plain</code>');
  });
  it('does nothing without a container or highlighter', () => {
    expect(() => highlightCodeBlocks(null, lowlight)).not.toThrow();
    expect(() => highlightCodeBlocks(document.createElement('div'), null)).not.toThrow();
  });
});
