import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import WikiEmbed, { clearWikiEmbedCache, WikiEmbedElement } from './WikiEmbed';
import { api } from '../api/client';
import ReactMarkdown from 'react-markdown';
import { remarkWikiLinks } from '../utils/remarkWikiLinks';

vi.mock('../api/client', () => ({ api: { getPageEmbed: vi.fn() } }));

const ok = (html) => ({ html, missing: false, restricted: false, truncated: false });

describe('WikiEmbed', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clearWikiEmbedCache();
  });

  it('renders the server html under a title link', async () => {
    api.getPageEmbed.mockResolvedValue(ok('<p>Body <strong>x</strong></p>'));
    const { container, findByText } = render(<WikiEmbed page="Target" section="Usage" />);
    await findByText('x');
    expect(container.querySelector('.wiki-embed-title a').getAttribute('href')).toMatch(/\/wiki\/Target#usage$/);
    expect(container.querySelector('.wiki-embed-title').textContent).toBe('Target › Usage');
  });

  it('shows the restricted state on 403 with a plain-text title', async () => {
    api.getPageEmbed.mockRejectedValue(Object.assign(new Error('Forbidden'), { status: 403 }));
    const { findByText, container } = render(<WikiEmbed page="Secret" />);
    await findByText("You don't have access to this page.");
    expect(container.querySelector('.wiki-embed-title a')).toBeNull();
  });

  it('shows the server-provided missing body', async () => {
    api.getPageEmbed.mockResolvedValue({ html: '<p class="wiki-embed-missing">Not created yet</p>', missing: true, restricted: false, truncated: false });
    expect(await render(<WikiEmbed page="Nope" />).findByText('Not created yet')).toBeTruthy();
  });

  it('shows an error state on other failures', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.getPageEmbed.mockRejectedValue(Object.assign(new Error('boom'), { status: 500 }));
    expect(await render(<WikiEmbed page="X" />).findByText('Embed could not be loaded')).toBeTruthy();
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it('dedupes identical embeds into one request', async () => {
    api.getPageEmbed.mockResolvedValue(ok('<p>b</p>'));
    render(<><WikiEmbed page="T" /><WikiEmbed page="T" /></>);
    await waitFor(() => expect(api.getPageEmbed).toHaveBeenCalledTimes(1));
  });

  it('keeps the injected html across an unrelated re-render', async () => {
    api.getPageEmbed.mockResolvedValue(ok('<p id="kept">b</p>'));
    const { rerender, findByText, container } = render(<WikiEmbed page="T" />);
    await findByText('b');
    container.querySelector('#kept').setAttribute('data-touched', '1');
    rerender(<WikiEmbed page="T" />);
    expect(container.querySelector('#kept').getAttribute('data-touched')).toBe('1');
  });

  it('adapter reads data-page and data-section', async () => {
    api.getPageEmbed.mockResolvedValue(ok('<p>z</p>'));
    const { findByText } = render(<WikiEmbedElement data-page="P" data-section="S" />);
    await findByText('z');
    expect(api.getPageEmbed).toHaveBeenCalledWith('P', expect.objectContaining({ section: 'S' }));
  });

  it('fetches an embed once while its target resolves to the canonical name (no second request)', async () => {
    api.getPageEmbed.mockResolvedValue(ok('<p>body</p>'));
    const md = (resolved) => (
      <ReactMarkdown components={{ 'wiki-embed': WikiEmbedElement }} remarkPlugins={[[remarkWikiLinks, { resolved }]]}>
        {'![[target page#Usage]]'}
      </ReactMarkdown>
    );
    const { rerender, findByText, container } = render(md(new Map()));
    await findByText('body');
    rerender(md(new Map([['target page', 'TargetPage']])));
    await waitFor(() => expect(container.querySelector('.wiki-embed-title').textContent).toBe('TargetPage › Usage'));
    expect(container.querySelector('.wiki-embed-title a').getAttribute('href')).toMatch(/\/wiki\/TargetPage#usage$/);
    expect(container.querySelector('.wiki-embed-body').textContent).toBe('body');
    expect(api.getPageEmbed).toHaveBeenCalledTimes(1);
    expect(api.getPageEmbed).toHaveBeenCalledWith('target page', expect.objectContaining({ section: 'Usage' }));
  });
});

