import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import FrontmatterPreview from './FrontmatterPreview';

const WITH_FM = '---\ntype: article\ncluster: x\ntags:\n- a\n---\n\n# Body\n';

describe('FrontmatterPreview', () => {
  it('renders nothing when there is no frontmatter', () => {
    const { container } = render(<FrontmatterPreview content="# Just a body" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows a collapsed summary with the top-level field count', () => {
    render(<FrontmatterPreview content={WITH_FM} />);
    const btn = screen.getByRole('button', { name: /Frontmatter/ });
    expect(btn).toHaveAttribute('aria-expanded', 'false');
    expect(btn).toHaveTextContent('3 fields'); // type, cluster, tags
    expect(screen.queryByText(/type: article/)).not.toBeInTheDocument();
  });

  it('expands to reveal the raw frontmatter', () => {
    render(<FrontmatterPreview content={WITH_FM} />);
    fireEvent.click(screen.getByRole('button', { name: /Frontmatter/ }));
    expect(screen.getByText(/type: article/)).toBeInTheDocument();
  });
});
