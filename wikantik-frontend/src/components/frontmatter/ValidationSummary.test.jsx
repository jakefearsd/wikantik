import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import ValidationSummary from './ValidationSummary';

describe('ValidationSummary', () => {
  it('shows a valid state when there are no violations', () => {
    render(<ValidationSummary violations={[]} validating={false} />);
    expect(screen.getByText(/frontmatter valid/i)).toBeTruthy();
  });

  it('shows error and warning counts and jumps to the first error field', () => {
    const onJump = vi.fn();
    render(
      <ValidationSummary
        violations={[
          { field: 'runbook.related_tools', severity: 'ERROR', code: 'a', message: 'x' },
          { field: 'summary', severity: 'WARNING', code: 'b', message: 'y' },
          { field: 'tags', severity: 'WARNING', code: 'c', message: 'z' },
        ]}
        validating={false}
        onJump={onJump}
      />,
    );
    expect(screen.getByText('1 error')).toBeTruthy();
    expect(screen.getByText('2 warnings')).toBeTruthy();
    fireEvent.click(screen.getByText('1 error'));
    expect(onJump).toHaveBeenCalledWith('runbook.related_tools');
  });

  it('shows a checking state while validating with no prior result', () => {
    const { container } = render(<ValidationSummary violations={[]} validating />);
    const checking = container.querySelector('.fm-summary-checking');
    expect(checking).toBeTruthy();
    expect(checking.textContent).toMatch(/checking/i);
  });
});
