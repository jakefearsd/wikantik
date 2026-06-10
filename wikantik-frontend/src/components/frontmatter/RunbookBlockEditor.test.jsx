import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import RunbookBlockEditor from './RunbookBlockEditor';

describe('RunbookBlockEditor', () => {
  it('renders a runbook.related_tools violation next to the related_tools control', () => {
    render(
      <RunbookBlockEditor
        value={{ related_tools: ['/admin/x'], when_to_use: ['a'] }}
        onChange={() => {}}
        violations={[
          { field: 'runbook.related_tools', severity: 'ERROR', code: 'related_tool_invalid', message: 'bad tool' },
        ]}
      />,
    );
    const field = screen.getByLabelText('runbook related_tools').closest('.fm-runbook-field');
    expect(field.textContent).toContain('bad tool');
    // an unrelated subfield shows no violation
    const other = screen.getByLabelText('runbook when_to_use').closest('.fm-runbook-field');
    expect(other.textContent).not.toContain('bad tool');
  });
});
