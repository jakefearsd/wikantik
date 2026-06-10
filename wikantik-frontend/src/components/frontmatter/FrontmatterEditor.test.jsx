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
});
