import { describe, it, expect } from 'vitest';
import { fillFenceEnvelope } from './envelope';

describe('fillFenceEnvelope', () => {
  it('buildsTheEnvelopeTheServerEmits', () => {
    const figure = fillFenceEnvelope(document.createElement('figure'), 'mermaid', 'flowchart TD');
    expect(figure.className).toBe('wk-fence');
    expect(figure.dataset.fence).toBe('mermaid');
    expect(figure.dataset.fenceKind).toBe('browser');
    expect(figure.children).toHaveLength(1);
    expect(figure.querySelector('pre.wk-fence-source > code.language-mermaid').textContent).toBe('flowchart TD');
  });

  it('marksAServerLanguageAsServerKind', () => {
    const figure = fillFenceEnvelope(document.createElement('figure'), 'plantuml', 'A -> B');
    expect(figure.dataset.fenceKind).toBe('server');
    expect(figure.querySelector('code.language-plantuml').textContent).toBe('A -> B');
  });

  it('treatsAnUnregisteredLanguageAsBrowserKind', () => {
    const figure = fillFenceEnvelope(document.createElement('figure'), 'ditaa', 'x');
    expect(figure.dataset.fenceKind).toBe('browser');
    expect(figure.dataset.fence).toBe('ditaa');
  });

  it('doesNotLetPrototypeNamesMatchARenderer', () => {
    for (const name of ['constructor', 'toString', '__proto__', 'hasOwnProperty']) {
      expect(fillFenceEnvelope(document.createElement('figure'), name, 'x').dataset.fenceKind).toBe('browser');
    }
  });

  it('putsTheSourceInAsTextNeverMarkup', () => {
    const source = '</code></pre><img src=x onerror=alert(1)><script>alert(2)</script>';
    const figure = fillFenceEnvelope(document.createElement('figure'), 'plantuml', source);
    expect(figure.querySelector('img')).toBeNull();
    expect(figure.querySelector('script')).toBeNull();
    expect(figure.querySelectorAll('pre')).toHaveLength(1);
    expect(figure.querySelector('code').textContent).toBe(source);
  });

  it('putsTheLanguageInAsAttributeAndClassTextOnly', () => {
    const figure = fillFenceEnvelope(document.createElement('figure'), 'a" onload="x', 's');
    expect(figure.getAttributeNames().sort()).toEqual(['class', 'data-fence', 'data-fence-kind']);
    expect(figure.dataset.fence).toBe('a" onload="x');
  });

  it('resetsAReusedFigureSoDrawFenceSeesItAsFresh', () => {
    const figure = document.createElement('figure');
    figure.className = 'wk-fence something-else';
    figure.dataset.fenceState = 'failed';
    figure.setAttribute('data-fence-error', 'syntax-error');
    figure.setAttribute('data-fence-error-line', '3');
    figure.setAttribute('data-fence-error-message', 'bad');
    figure.innerHTML = '<div class="wk-fence-drawing">old</div><div class="wk-fence-error">err</div><pre>stale</pre>';

    const out = fillFenceEnvelope(figure, 'plantuml', 'B -> C');

    expect(out).toBe(figure);
    expect(figure.className).toBe('wk-fence');
    expect(figure.hasAttribute('data-fence-state')).toBe(false);
    expect(figure.hasAttribute('data-fence-error')).toBe(false);
    expect(figure.hasAttribute('data-fence-error-line')).toBe(false);
    expect(figure.hasAttribute('data-fence-error-message')).toBe(false);
    expect(figure.children).toHaveLength(1);
    expect(figure.querySelector('.wk-fence-drawing')).toBeNull();
    expect(figure.querySelector('code').textContent).toBe('B -> C');
  });

  it('refillingWithADifferentLanguageUpdatesTheKind', () => {
    const figure = fillFenceEnvelope(document.createElement('figure'), 'plantuml', 'A');
    expect(figure.dataset.fenceKind).toBe('server');
    fillFenceEnvelope(figure, 'mermaid', 'B');
    expect(figure.dataset.fenceKind).toBe('browser');
    expect(figure.dataset.fence).toBe('mermaid');
    expect(figure.querySelector('code').className).toBe('language-mermaid');
  });
});
