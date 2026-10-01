import { describe, it, expect } from 'vitest';
import { interceptableHref, isSpaPath } from './navigationGuard';

const loc = { href: 'http://w.test/edit/Page', origin: 'http://w.test', pathname: '/edit/Page', search: '' };

function clickOn(html, init = {}) {
  const host = document.createElement('div');
  host.innerHTML = html;
  const a = host.querySelector('a') || host.firstElementChild;
  return { target: a.querySelector('span') || a, button: 0, metaKey: false, ctrlKey: false, shiftKey: false,
           altKey: false, defaultPrevented: false, ...init };
}

describe('interceptableHref', () => {
  it.each([
    ['plain in-app link', '<a href="/wiki/Other">x</a>', {}, '/wiki/Other'],
    ['click on a child element of the link', '<a href="/wiki/Other"><span>x</span></a>', {}, '/wiki/Other'],
    ['keeps query and hash', '<a href="/search?q=a#r">x</a>', {}, '/search?q=a#r'],
    ['relative href resolves against the current page', '<a href="Other">x</a>', {}, '/edit/Other'],
    ['ctrl-click opens a tab', '<a href="/wiki/Other">x</a>', { ctrlKey: true }, null],
    ['cmd-click opens a tab', '<a href="/wiki/Other">x</a>', { metaKey: true }, null],
    ['shift-click', '<a href="/wiki/Other">x</a>', { shiftKey: true }, null],
    ['middle button', '<a href="/wiki/Other">x</a>', { button: 1 }, null],
    ['target=_blank', '<a href="/wiki/Other" target="_blank">x</a>', {}, null],
    ['download link', '<a href="/attach/Page/f.pdf" download>x</a>', {}, null],
    ['other origin', '<a href="https://example.com/">x</a>', {}, null],
    ['same-page hash jump', '<a href="#section">x</a>', {}, null],
    ['already handled', '<a href="/wiki/Other">x</a>', { defaultPrevented: true }, null],
    ['not a link', '<button>x</button>', {}, null],
  ])('%s', (_name, html, init, expected) => {
    expect(interceptableHref(clickOn(html, init), loc, '')).toBe(expected);
  });

  it('strips a non-root basename', () => {
    const based = { ...loc, href: 'http://w.test/wk/edit/Page', pathname: '/wk/edit/Page' };
    expect(interceptableHref(clickOn('<a href="/wk/wiki/Other">x</a>'), based, '/wk')).toBe('/wiki/Other');
  });
});

describe('isSpaPath', () => {
  it.each([
    '/', '/wiki/Main', '/wiki', '/edit/Page', '/diff/Page?v=2', '/search?q=x', '/page-graph', '/knowledge-graph',
    '/preferences', '/me/mentions', '/reset-password?t=1', '/login', '/change-password', '/admin',
    '/admin/users', '/wiki/Page#section',
  ])('%s is a router route', (path) => { expect(isSpaPath(path)).toBe(true); });

  it.each([
    '/attach/Page/file.pdf', '/privacy-policy.html', '/sparql', '/export/ontology.ttl', '/api/pages/X',
    '/wikipedia', '/administrator', '/searchable', '/login.html', '/id/page/abc',
  ])('%s is not a router route', (path) => { expect(isSpaPath(path)).toBe(false); });
});
