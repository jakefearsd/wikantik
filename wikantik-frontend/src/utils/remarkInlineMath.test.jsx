import { describe, it, expect } from 'vitest';
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkMath from 'remark-math';
import ReactMarkdown from 'react-markdown';
import { renderToStaticMarkup } from 'react-dom/server';
import rehypeKatex from 'rehype-katex';
import remarkInlineMath from './remarkInlineMath';
import { CASES } from './inlineMath.cases';

const parser = unified().use(remarkParse).use(remarkMath, { singleDollarTextMath: false }).use(remarkInlineMath);

function collect(node, type, out = []) {
  if (node.type === type) out.push(node);
  (node.children || []).forEach((c) => collect(c, type, out));
  return out;
}
const maths = (src) => collect(parser.parse(src), 'inlineMath').map((n) => n.value);

describe('remarkInlineMath', () => {
  // `$$x$$` is not single-dollar inline math; remark-math keeps its own `$$..$$` text math (tested below).
  it.each(CASES.filter(([src]) => src !== '$$x$$'))('%j', (src, expected) => {
    expect(maths(src)).toEqual(expected);
  });

  it('keeps asterisks inside math (no emphasis)', () => {
    const tree = parser.parse('$a*b*c$');
    expect(collect(tree, 'inlineMath').map((n) => n.value)).toEqual(['a*b*c']);
    expect(collect(tree, 'emphasis')).toEqual([]);
  });
  it('leaves inline code alone', () => {
    const tree = parser.parse('`$x$`');
    expect(collect(tree, 'inlineMath')).toEqual([]);
    expect(collect(tree, 'inlineCode').map((n) => n.value)).toEqual(['$x$']);
  });
  it('allows math inside strong', () => {
    const tree = parser.parse('**$x$**');
    expect(collect(collect(tree, 'strong')[0], 'inlineMath').map((n) => n.value)).toEqual(['x']);
  });
  it('still parses display math', () => {
    const tree = parser.parse('$$\nx\n$$');
    expect(collect(tree, 'math').map((n) => n.value)).toEqual(['x']);
    expect(collect(tree, 'inlineMath')).toEqual([]);
  });
  it('still parses $$..$$ text math from remark-math', () => {
    expect(maths('a $$x$$ b')).toEqual(['x']);
  });
  it('renders katex for $x$ but not for currency', () => {
    const render = (src) => renderToStaticMarkup(
      <ReactMarkdown remarkPlugins={[[remarkMath, { singleDollarTextMath: false }], remarkInlineMath]} rehypePlugins={[rehypeKatex]}>{src}</ReactMarkdown>,
    );
    expect(render('costs $5 and $10')).not.toContain('katex');
    expect(render('so $x$ ok')).toContain('katex');
  });
});
