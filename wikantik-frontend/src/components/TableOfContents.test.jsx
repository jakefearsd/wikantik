import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import TableOfContents from './TableOfContents';

const makeHeadings = (n) =>
  Array.from({ length: n }, (_, i) => ({
    id: `heading-${i + 1}`,
    text: `Heading ${i + 1}`,
    level: 2,
  }));

describe('TableOfContents', () => {
  it('returns null when headings count < 3', () => {
    const { container } = render(<TableOfContents headings={makeHeadings(2)} />);
    expect(container.firstChild).toBeNull();
  });

  it('returns null for empty headings', () => {
    const { container } = render(<TableOfContents headings={[]} />);
    expect(container.firstChild).toBeNull();
  });

  it('renders a nav with aria-label when headings >= 3', () => {
    render(<TableOfContents headings={makeHeadings(3)} />);
    expect(screen.getByRole('navigation', { name: 'Table of contents' })).toBeInTheDocument();
  });

  it('renders anchor links for each heading', () => {
    render(<TableOfContents headings={makeHeadings(4)} />);
    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(4);
    expect(links[0]).toHaveAttribute('href', '#heading-1');
    expect(links[3]).toHaveAttribute('href', '#heading-4');
  });

  it('marks activeId item with aria-current="true" and class toc-active', () => {
    render(<TableOfContents headings={makeHeadings(3)} activeId="heading-2" />);
    const links = screen.getAllByRole('link');
    expect(links[1]).toHaveAttribute('aria-current', 'true');
    expect(links[1].closest('li')).toHaveClass('toc-active');
    expect(links[0]).not.toHaveAttribute('aria-current');
    expect(links[2]).not.toHaveAttribute('aria-current');
  });

  it('renders h2 and h3 headings in a nested structure', () => {
    const headings = [
      { id: 'intro', text: 'Intro', level: 2 },
      { id: 'sub', text: 'Sub section', level: 3 },
      { id: 'next', text: 'Next', level: 2 },
    ];
    render(<TableOfContents headings={headings} />);
    expect(screen.getByText('Intro')).toBeInTheDocument();
    expect(screen.getByText('Sub section')).toBeInTheDocument();
    expect(screen.getByText('Next')).toBeInTheDocument();
  });
});
