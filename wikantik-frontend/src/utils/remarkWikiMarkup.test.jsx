import { describe, it, expect, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { remarkWikiMarkup, pluginChipLabel } from './remarkWikiMarkup';

const html = (md) => renderToStaticMarkup(
  <ReactMarkdown remarkPlugins={[remarkGfm, remarkWikiMarkup]}>{md}</ReactMarkdown>,
);

describe('pluginChipLabel', () => {
  it.each([
    ['TableOfContents', '⚙ TableOfContents'],
    ['com.example.plugins.Weather city=Oslo', '⚙ Weather'],
    ['ALLOW view Admin', '🔒 ALLOW view Admin'],
    ['DENY edit Guest', '🔒 DENY edit Guest'],
    ['SET alias=Foo', '≔ SET alias=Foo'],
    ['$username', '$username'],
  ])('%s → %s', (inner, label) => expect(pluginChipLabel(inner)).toBe(label));
});

describe('remarkWikiMarkup', () => {
  it('renders [{X}]() as a chip with the full markup in its title', () => {
    const out = html('Before [{TableOfContents}]() after');
    expect(out).toContain('class="wiki-plugin-chip"');
    expect(out).toContain('title="[{TableOfContents}]"');
    expect(out).toContain('⚙ TableOfContents');
    expect(out).not.toContain('<a ');
  });
  it('renders bare [{X}] as a chip', () => {
    expect(html('[{ALLOW view Admin}]\n\nBody')).toContain('🔒 ALLOW view Admin');
  });
  it('leaves plugin syntax inside code alone', () => {
    const out = html('`[{TableOfContents}]()`\n\n```\n[{ALLOW view Admin}]\n```');
    expect(out).not.toContain('wiki-plugin-chip');
  });
  it('adds a citation badge after a cite:// link', () => {
    const out = html('A [claim](cite://TargetPage/setup-steps "the quoted span") here.');
    expect(out).toContain('class="wiki-cite-badge"');
    expect(out).toContain('↗ TargetPage § setup-steps');
    expect(out).toContain('title="the quoted span"');
  });
  it('keeps the raw text and warns when a cite target has a malformed escape', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const out = html('A [claim](cite://Bad%E0%A4%A/h "s") here.');
    expect(out).toContain('class="wiki-cite-badge"');
    expect(out).toContain('↗ Bad%E0%A4%A § h');
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('[link-targets]'), expect.anything(), expect.anything());
    warn.mockRestore();
  });
});
