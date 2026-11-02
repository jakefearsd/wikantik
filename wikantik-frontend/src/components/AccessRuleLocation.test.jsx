import { render } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import AccessRuleLocation from './AccessRuleLocation';

describe('AccessRuleLocation (#104)', () => {
  it('names the body line when the server sends one', () => {
    const { container } = render(<AccessRuleLocation line={4} field={null} />);
    expect(container).toHaveTextContent(/^Line 4$/);
  });

  it('names the frontmatter field of a rule there', () => {
    const { container } = render(<AccessRuleLocation field="summary" />);
    expect(container).toHaveTextContent(/^Frontmatter field summary$/);
    expect(container.querySelector('code')).toHaveTextContent('summary');
  });

  it('says only "Frontmatter" for a frontmatter line no field owns', () => {
    const { container } = render(<AccessRuleLocation />);
    expect(container).toHaveTextContent(/^Frontmatter$/);
  });
});
