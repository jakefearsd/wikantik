import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import DerivedProvenanceBanner from './DerivedProvenanceBanner';

describe('DerivedProvenanceBanner', () => {
  it('renders nothing for non-derived pages', () => {
    const { container } = render(<DerivedProvenanceBanner metadata={{}} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders source link, connector and machine-managed note', () => {
    render(<DerivedProvenanceBanner metadata={{
      derived_from: 'https://eng.atlassian.net/wiki/x',
      derived_connector: 'team-confluence',
      derived_source_url: 'https://eng.atlassian.net/wiki/x',
    }} lastModified="2026-07-15T06:00:00Z" />);
    const link = screen.getByRole('link', { name: /eng\.atlassian\.net/ });
    expect(link).toHaveAttribute('href', 'https://eng.atlassian.net/wiki/x');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.getByText(/team-confluence/)).toBeInTheDocument();
    expect(screen.getByText(/machine-managed/i)).toBeInTheDocument();
  });

  it('refuses non-http(s) source_url schemes (no link rendered)', () => {
    render(<DerivedProvenanceBanner metadata={{
      derived_from: 'report.pdf',
      derived_source_url: 'javascript:alert(1)',
    }} />);
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getByText(/report\.pdf/)).toBeInTheDocument();
  });

  it('falls back to derived_from text when no source_url and shows orphaned state', () => {
    render(<DerivedProvenanceBanner metadata={{ derived_from: 'report.pdf', derived_orphaned: true }} />);
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getByText(/report\.pdf/)).toBeInTheDocument();
    expect(screen.getByText(/no longer syncing/i)).toBeInTheDocument();
  });
});
