import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import ContributorList from './ContributorList.jsx';
import { labelOf } from './remoteCursors.js';

describe('ContributorList', () => {
  it('contributorListTruncatesWithATitle', () => {
    render(<ContributorList names={['Ana', 'Raj', 'Kim', 'Lee']} />);
    const el = screen.getByText('Ana, Raj and 2 others');
    expect(el.getAttribute('title')).toBe('Ana, Raj, Kim, Lee');
  });

  it.each([
    [['Ana'], 'Ana'],
    [['Ana', 'Raj'], 'Ana and Raj'],
    [['Ana', 'Raj', 'Kim'], 'Ana, Raj and Kim'],
  ])('lists up to max names in full (%j)', (names, text) => {
    render(<ContributorList names={names} />);
    expect(screen.getByText(text)).toBeTruthy();
  });

  it('honours max', () => {
    render(<ContributorList names={['Ana', 'Raj', 'Kim']} max={2} />);
    expect(screen.getByText('Ana and 2 others')).toBeTruthy();
  });

  it('renders nothing for no names, drops duplicates and uses the shared label rule', () => {
    const { container } = render(<ContributorList names={[]} />);
    expect(container.textContent).toBe('');
    render(<ContributorList names={['  Ana ', 'Ana', 42, '']} />);
    expect(screen.getByText('Ana')).toBeTruthy();
    expect(labelOf('  Ana ')).toBe('Ana');
  });
});
