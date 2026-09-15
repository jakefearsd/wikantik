import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import MetadataPanel from './MetadataPanel';

const renderPanel = (metadata) =>
  render(
    <MemoryRouter>
      <MetadataPanel metadata={metadata} />
    </MemoryRouter>
  );

describe('MetadataPanel', () => {
  it('renders nothing when metadata is null', () => {
    const { container } = renderPanel(null);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when metadata is not an object', () => {
    const { container } = renderPanel('not-an-object');
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when metadata has no usable keys', () => {
    const { container } = renderPanel({ ignored: null, alsoIgnored: undefined });
    expect(container).toBeEmptyDOMElement();
  });

  it('renders collapsed by default with a toggle button', () => {
    renderPanel({ type: 'article' });
    expect(screen.getByText(/Properties/)).toBeInTheDocument();
    expect(screen.queryByText('Type')).not.toBeInTheDocument();
  });

  it('expands to show fields in KEY_ORDER, then unordered extras sorted alphabetically', () => {
    renderPanel({
      zebra: 'z-value',
      type: 'article',
      status: 'draft',
      apple: 'a-value',
    });
    fireEvent.click(screen.getByText(/Properties/));

    const labels = screen.getAllByText(/Type|Status|Apple|Zebra/).map((el) => el.textContent);
    // KEY_ORDER fields (type, status) come first, then remaining keys alphabetically (apple, zebra).
    expect(labels).toEqual(['Type', 'Status', 'Apple', 'Zebra']);
    expect(screen.getByText('article')).toBeInTheDocument();
    expect(screen.getByText('draft')).toBeInTheDocument();
  });

  it('renders tags as links to the search page', () => {
    renderPanel({ tags: ['foo', 'bar baz'] });
    fireEvent.click(screen.getByText(/Properties/));

    expect(screen.getByRole('link', { name: 'foo' })).toHaveAttribute('href', '/search?q=foo');
    expect(screen.getByRole('link', { name: 'bar baz' })).toHaveAttribute('href', '/search?q=bar%20baz');
  });

  it('renders related as links to wiki pages', () => {
    renderPanel({ related: ['OtherPage'] });
    fireEvent.click(screen.getByText(/Properties/));

    expect(screen.getByRole('link', { name: 'OtherPage' })).toHaveAttribute('href', '/wiki/OtherPage');
  });

  it('renders an em dash for an empty array value', () => {
    renderPanel({ tags: [] });
    fireEvent.click(screen.getByText(/Properties/));
    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('joins a non-tags/related array with commas', () => {
    renderPanel({ audience: ['humans', 'agents'] });
    fireEvent.click(screen.getByText(/Properties/));
    expect(screen.getByText('humans, agents')).toBeInTheDocument();
  });

  it('renders the summary in its own section when present', () => {
    renderPanel({ summary: 'A short description.' });
    fireEvent.click(screen.getByText(/Properties/));
    expect(screen.getByText('Summary')).toBeInTheDocument();
    expect(screen.getByText('A short description.')).toBeInTheDocument();
  });

  it('does not render a Summary section when summary is empty', () => {
    renderPanel({ summary: '', type: 'article' });
    fireEvent.click(screen.getByText(/Properties/));
    expect(screen.queryByText('Summary')).not.toBeInTheDocument();
  });

  it('collapses again on a second click', () => {
    renderPanel({ type: 'article' });
    const toggle = screen.getByText(/Properties/);
    fireEvent.click(toggle);
    expect(screen.getByText('article')).toBeInTheDocument();
    fireEvent.click(toggle);
    expect(screen.queryByText('article')).not.toBeInTheDocument();
  });
});
