import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// jsdom cannot lay anything out, so the class contract is asserted on the rule text; WorkBoardIT (681 px window) is the
// real-browser check. Ruling P22: a centred dialog always fits the window, so its primary button is always reachable.
const globals = readFileSync(resolve(process.cwd(), 'src/styles/globals.css'), 'utf8');
const admin = readFileSync(resolve(process.cwd(), 'src/styles/admin.css'), 'utf8');

/** The declaration block of the first rule whose selector list is exactly `selector`. */
function block(css, selector) {
  const esc = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const m = new RegExp(`(?:^|\\n)${esc}\\s*\\{([^}]*)\\}`).exec(css);
  return m ? m[1] : null;
}

describe('centred modal sizing (P22)', () => {
  it('the combined selector drops the legacy 15vh top padding that the centred dialog inherits', () => {
    const rule = block(globals, '.modal-overlay.modal-overlay-centered');
    expect(rule).not.toBeNull();
    expect(rule).toMatch(/padding:\s*var\(--space-md\)/);
    expect(rule).not.toMatch(/15vh/);
    expect(rule).toMatch(/align-items:\s*center/);
  });

  it('the content height is the viewport minus the overlay padding, dvh with a vh fallback, and scrolls inside', () => {
    const rule = block(globals, '.modal-overlay-centered .modal-content');
    expect(rule).not.toBeNull();
    const vh = rule.indexOf('max-height: calc(100vh - 2 * var(--space-md))');
    const dvh = rule.indexOf('max-height: calc(100dvh - 2 * var(--space-md))');
    expect(vh).toBeGreaterThanOrEqual(0);
    expect(dvh).toBeGreaterThan(vh); // the later declaration wins where dvh is supported
    expect(rule).not.toMatch(/max-height:\s*90vh/);
    expect(rule).toMatch(/overflow-y:\s*auto/);
  });

  it('non-centred overlays keep their legacy top offset', () => {
    const rule = block(admin, '.modal-overlay');
    expect(rule).toMatch(/padding-top:\s*15vh/);
    expect(rule).toMatch(/align-items:\s*flex-start/);
  });
});
