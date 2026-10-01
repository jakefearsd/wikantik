import { render } from '@testing-library/react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { describe, it, expect } from 'vitest';
import remarkCallouts from './remarkCallouts';
import cases from './__fixtures__/callouts.json';

function describeCallouts(container, parentCallout = null) {
  return [...container.querySelectorAll('.callout')]
    .filter((el) => (el.parentElement.closest('.callout') || null) === parentCallout)
    .map((el) => {
      const inner = el.querySelector(':scope > .callout-title .callout-title-inner');
      const content = el.querySelector(':scope > .callout-content');
      const clone = content.cloneNode(true);
      clone.querySelectorAll('.callout').forEach((n) => n.remove());
      return {
        tag: el.tagName.toLowerCase(),
        style: el.getAttribute('data-callout'),
        open: el.hasAttribute('open'),
        title: inner.textContent.trim(),
        titleTags: [...inner.querySelectorAll('*')].map((n) => n.tagName.toLowerCase()),
        content: clone.textContent.replace(/\s+/g, ' ').trim(),
        children: describeCallouts(content, el),
      };
    });
}

describe('remarkCallouts', () => {
  it.each(cases.map((c) => [c.name, c]))('matches the shared server fixture: %s', (_name, c) => {
    const { container } = render(
      <ReactMarkdown remarkPlugins={[remarkGfm, remarkCallouts]}>{c.markdown}</ReactMarkdown>,
    );
    expect(describeCallouts(container)).toEqual(c.expected);
  });

  it('emits the documented classes and the icon span', () => {
    const { container } = render(
      <ReactMarkdown remarkPlugins={[remarkCallouts]}>{'> [!danger] Stop\n> Now.\n'}</ReactMarkdown>,
    );
    const el = container.querySelector('div.callout.callout-danger');
    expect(el).not.toBeNull();
    expect(el.querySelector(':scope > .callout-title > .callout-icon').getAttribute('aria-hidden')).toBe('true');
    expect(container.querySelector('blockquote')).toBeNull();
  });
});
