// FrontmatterEditor.test.jsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import FrontmatterEditor from './FrontmatterEditor';

const SCHEMA = {
  fields: [
    { key: 'type', label: 'Type', widget: 'ENUM', canonicalValues: ['article', 'hub'], open: true },
    {
      key: 'status',
      label: 'Status',
      widget: 'ENUM',
      canonicalValues: ['draft', 'active', 'archived'],
      open: true,
      suggestionMap: { published: 'active' },
    },
    { key: 'audience', label: 'Audience', widget: 'ENUM', canonicalValues: ['humans', 'agents', 'both'], open: false },
    { key: 'summary', label: 'Summary', widget: 'TEXT', minLen: 50, maxLen: 160 },
  ],
};

describe('FrontmatterEditor', () => {
  it('renders an open enum as a combobox and a closed enum as a select', () => {
    render(<FrontmatterEditor schema={SCHEMA} metadata={{ type: 'article', audience: 'both' }} onChange={() => {}} />);
    // open enum -> combobox input labeled "Type"
    expect(screen.getByLabelText('Type').getAttribute('role')).toBe('combobox');
    // closed enum -> native <select> labeled "Audience"
    expect(screen.getByLabelText('Audience').tagName).toBe('SELECT');
  });

  it('shows an inline violation and applies its suggestion to the field', () => {
    const onChange = vi.fn();
    render(
      <FrontmatterEditor
        schema={SCHEMA}
        metadata={{ status: 'published' }}
        onChange={onChange}
        violations={[
          {
            field: 'status',
            severity: 'WARNING',
            code: 'status.noncanonical',
            message: 'not canonical',
            suggestion: 'active',
          },
        ]}
      />,
    );
    expect(screen.getByText('not canonical')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /use/i }));
    expect(onChange).toHaveBeenCalledWith({ status: 'active' });
  });

  it('lists unknown keys in Advanced and preserves them when editing a known field', () => {
    const onChange = vi.fn();
    render(
      <FrontmatterEditor schema={SCHEMA} metadata={{ type: 'article', custom_key: 'val' }} onChange={onChange} />,
    );
    expect(screen.getByText('custom_key')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Summary'), { target: { value: 'a new summary' } });
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ custom_key: 'val', summary: 'a new summary' }),
    );
  });

  it('shows the YAML in the Raw tab', () => {
    render(<FrontmatterEditor schema={SCHEMA} metadata={{ type: 'article' }} onChange={() => {}} />);
    fireEvent.click(screen.getByRole('tab', { name: 'Raw YAML' }));
    expect(screen.getByLabelText('Raw frontmatter YAML').value).toContain('type');
  });

  it('syncs Raw edits back to the form via validateRaw on blur', async () => {
    const onChange = vi.fn();
    const validateRaw = vi.fn().mockResolvedValue({ metadata: { type: 'hub' }, violations: [] });
    render(
      <FrontmatterEditor schema={SCHEMA} metadata={{ type: 'article' }} onChange={onChange} validateRaw={validateRaw} />,
    );
    fireEvent.click(screen.getByRole('tab', { name: 'Raw YAML' }));
    const textarea = screen.getByLabelText('Raw frontmatter YAML');
    fireEvent.change(textarea, { target: { value: 'type: hub' } });
    fireEvent.blur(textarea);
    await waitFor(() => expect(onChange).toHaveBeenCalledWith({ type: 'hub' }));
  });

  it('tags each field wrapper with data-field and marks wide fields', () => {
    const schema = {
      fields: [
        { key: 'type', label: 'Type', widget: 'ENUM', canonicalValues: ['article'], open: true },
        { key: 'summary', label: 'Summary', widget: 'TEXT', minLen: 50, maxLen: 160 },
      ],
    };
    const { container } = render(
      <FrontmatterEditor schema={schema} metadata={{ type: 'article' }} onChange={() => {}} />,
    );
    expect(container.querySelector('[data-field="type"]')).toBeTruthy();
    const summary = container.querySelector('[data-field="summary"]');
    expect(summary.className).toContain('fm-field--wide');
    const type = container.querySelector('[data-field="type"]');
    expect(type.className).not.toContain('fm-field--wide');
  });

  it('routes runbook.* violations into the runbook block editor', () => {
    const schema = { fields: [{ key: 'runbook', label: 'Runbook', widget: 'RUNBOOK_BLOCK' }] };
    render(
      <FrontmatterEditor
        schema={schema}
        metadata={{ runbook: { related_tools: ['/admin/x'] } }}
        onChange={() => {}}
        violations={[{ field: 'runbook.related_tools', severity: 'ERROR', code: 'x', message: 'bad tool' }]}
      />,
    );
    const field = screen.getByLabelText('runbook related_tools').closest('.fm-runbook-field');
    expect(field.textContent).toContain('bad tool');
  });

  it('renders Common fields inline and collapses the rest into "More fields"', () => {
    const schema = {
      fields: [
        { key: 'title', label: 'Title', widget: 'TEXT' },
        { key: 'type', label: 'Type', widget: 'ENUM', canonicalValues: ['article'], open: true },
        { key: 'audience', label: 'Audience', widget: 'ENUM', canonicalValues: ['both'], open: false },
        { key: 'runbook', label: 'Runbook', widget: 'RUNBOOK_BLOCK' },
      ],
    };
    const { container } = render(
      <FrontmatterEditor schema={schema} metadata={{ title: 'X' }} onChange={() => {}} />,
    );
    // Common fields live in the first (always-open) .fm-form grid
    const commonGrid = container.querySelector('.fm-form');
    expect(commonGrid.querySelector('[data-field="title"]')).toBeTruthy();
    expect(commonGrid.querySelector('[data-field="type"]')).toBeTruthy();
    // Non-common editable fields live inside a closed "More fields" disclosure
    const more = container.querySelector('details.fm-more');
    expect(more).toBeTruthy();
    expect(more.open).toBe(false);
    expect(more.querySelector('[data-field="audience"]')).toBeTruthy();
    expect(more.querySelector('[data-field="runbook"]')).toBeTruthy();
  });

  it('shows the count of populated fields in the "More fields" summary without opening it', () => {
    const schema = {
      fields: [
        { key: 'title', label: 'Title', widget: 'TEXT' },
        { key: 'date', label: 'Date', widget: 'DATE' },
        { key: 'audience', label: 'Audience', widget: 'ENUM', canonicalValues: ['both'], open: false },
      ],
    };
    const { container } = render(
      <FrontmatterEditor
        schema={schema}
        metadata={{ title: 'X', date: '2026-04-25', audience: 'both' }}
        onChange={() => {}}
      />,
    );
    const summary = container.querySelector('.fm-more-summary');
    expect(summary.textContent).toContain('2 set');
    // the count is only a signal — it must NOT auto-open the disclosure
    expect(container.querySelector('details.fm-more').open).toBe(false);
  });

  it('omits the count when no "More" field has a value', () => {
    const schema = {
      fields: [
        { key: 'title', label: 'Title', widget: 'TEXT' },
        { key: 'audience', label: 'Audience', widget: 'ENUM', canonicalValues: ['both'], open: false },
      ],
    };
    const { container } = render(
      <FrontmatterEditor schema={schema} metadata={{ title: 'X' }} onChange={() => {}} />,
    );
    const summary = container.querySelector('.fm-more-summary');
    expect(summary.textContent).toContain('More fields');
    expect(summary.textContent).not.toContain('set');
  });

  it('shows populated read-only fields in the meta strip and hides empty ones', () => {
    const schema = {
      fields: [
        { key: 'title', label: 'Title', widget: 'TEXT' },
        { key: 'confidence', label: 'Confidence', widget: 'READONLY' },
        { key: 'agent_hints', label: 'Agent hints', widget: 'READONLY' },
      ],
    };
    const { container } = render(
      <FrontmatterEditor schema={schema} metadata={{ title: 'X', confidence: 0.82 }} onChange={() => {}} />,
    );
    const strip = container.querySelector('.fm-meta-strip');
    expect(strip).toBeTruthy();
    expect(strip.textContent).toContain('Confidence');
    expect(strip.textContent).toContain('0.82');
    expect(strip.textContent).not.toContain('Agent hints');
    // read-only fields must NOT also render as editable field widgets
    expect(container.querySelector('[data-field="confidence"]')).toBeFalsy();
  });

  it('renders no meta strip when no read-only field has a value', () => {
    const schema = {
      fields: [
        { key: 'title', label: 'Title', widget: 'TEXT' },
        { key: 'confidence', label: 'Confidence', widget: 'READONLY' },
      ],
    };
    const { container } = render(
      <FrontmatterEditor schema={schema} metadata={{ title: 'X' }} onChange={() => {}} />,
    );
    expect(container.querySelector('.fm-meta-strip')).toBeFalsy();
  });
});
