import { createElement } from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import CommentBody from '../components/CommentBody';
import { splitMentions, buildMentionToken, flattenMentions } from './mentionTokens';

describe('splitMentions', () => {
  it('returns no segments for an empty body', () => {
    expect(splitMentions('')).toEqual([]);
    expect(splitMentions(null)).toEqual([]);
    expect(splitMentions(undefined)).toEqual([]);
  });

  it('returns one text segment when there is no token', () => {
    expect(splitMentions('just words')).toEqual([{ type: 'text', text: 'just words' }]);
  });

  it('splits text around a token', () => {
    expect(splitMentions('hi @[Alice A](user:u-1) please look')).toEqual([
      { type: 'text', text: 'hi ' },
      { type: 'mention', uid: 'u-1', snapshot: 'Alice A' },
      { type: 'text', text: ' please look' },
    ]);
  });

  it('finds several tokens, including adjacent ones', () => {
    const segs = splitMentions('@[A](user:1)@[B](user:2)');
    expect(segs.map((s) => s.type)).toEqual(['mention', 'mention']);
    expect(segs.map((s) => s.uid)).toEqual(['1', '2']);
  });

  it('leaves a legacy @login inside the text segment', () => {
    expect(splitMentions('ping @alice now')).toEqual([{ type: 'text', text: 'ping @alice now' }]);
  });

  it('does not treat malformed tokens as mentions', () => {
    for (const bad of [
      '@[Alice](user:)',
      '@[](user:u1)',
      '@[Alice](user:bad uid)',
      '@[Alice](user:u1',
      '@[Ali\nce](user:u1)',
      `@[${'x'.repeat(65)}](user:u1)`,
      `@[Alice](user:${'u'.repeat(65)})`,
    ]) {
      expect(splitMentions(bad).every((s) => s.type === 'text')).toBe(true);
    }
  });

  it('accepts the 64-character bounds', () => {
    const name = 'n'.repeat(64);
    const uid = 'u'.repeat(64);
    expect(splitMentions(`@[${name}](user:${uid})`)).toEqual([{ type: 'mention', uid, snapshot: name }]);
  });

  it('accepts negative numeric and hyphenated uids', () => {
    expect(splitMentions('@[Admin](user:-6852820166199419346)')[0].uid).toBe('-6852820166199419346');
    expect(splitMentions('@[Agents](user:agents-service-account)')[0].uid).toBe('agents-service-account');
  });
});

describe('buildMentionToken', () => {
  it('builds the stored token form', () => {
    expect(buildMentionToken('u-1', 'Alice A')).toBe('@[Alice A](user:u-1)');
  });

  it('round-trips through splitMentions', () => {
    const seg = splitMentions(buildMentionToken('u-9', 'Zoë Ng'))[0];
    expect(seg).toEqual({ type: 'mention', uid: 'u-9', snapshot: 'Zoë Ng' });
  });

  it('strips brackets and control characters from the snapshot', () => {
    expect(buildMentionToken('u-1', 'A[li]ce\nB')).toBe('@[AliceB](user:u-1)');
  });

  it('truncates the snapshot to 64 characters', () => {
    const token = buildMentionToken('u-1', 'x'.repeat(80));
    expect(token).toBe(`@[${'x'.repeat(64)}](user:u-1)`);
    expect(splitMentions(token)[0].type).toBe('mention');
  });

  it('never splits a surrogate pair when truncating', () => {
    const token = buildMentionToken('u-1', '😀'.repeat(40));
    const seg = splitMentions(token)[0];
    expect(seg.type).toBe('mention');
    expect(seg.snapshot.length).toBeLessThanOrEqual(64);
    expect(seg.snapshot).toBe('😀'.repeat(32));
  });

  it('falls back to a neutral snapshot when nothing printable is left', () => {
    expect(buildMentionToken('u-1', '[]')).toBe('@[user](user:u-1)');
    expect(buildMentionToken('u-1', null)).toBe('@[user](user:u-1)');
  });
});

describe('flattenMentions', () => {
  it('returns an empty string for empty input', () => {
    expect(flattenMentions('')).toBe('');
    expect(flattenMentions(null)).toBe('');
  });

  it('shows the snapshot when no current name is known', () => {
    expect(flattenMentions('hi @[Old Name](user:u-1)!')).toBe('hi @Old Name!');
  });

  it('prefers the current name from the map', () => {
    expect(flattenMentions('hi @[Old Name](user:u-1)!', { 'u-1': 'New Name' })).toBe('hi @New Name!');
  });

  it('leaves legacy @login text alone', () => {
    expect(flattenMentions('hi @alice')).toBe('hi @alice');
  });

  it('drops a token that the snippet length cut in half', () => {
    expect(flattenMentions('see @[Alice A](user:u-1) and @[Bob B](user:u-')).toBe('see @Alice A and …');
    expect(flattenMentions('see @[Bob B')).toBe('see …');
    expect(flattenMentions('see @[Bob B](')).toBe('see …');
  });
});

// (createElement, not JSX: this is a .js file.)
// A token's snapshot is attacker-controlled text (anyone can type `@[Jake Fear](user:<any uid>)`),
// so the chip must show the name the server resolves for the uid, never the snapshot, and a uid the
// server did not resolve is not a chip at all.
describe('forged or stale mention tokens', () => {
  it('renders a known uid with the current name, never the snapshot', () => {
    render(createElement(CommentBody, { body: 'hi @[Jake Fear](user:u-1)', mentionNames: { 'u-1': 'Mallory' } }));
    expect(screen.getByText('@Mallory')).toBeInTheDocument();
    expect(screen.queryByText(/Jake Fear/)).not.toBeInTheDocument();
  });

  it('renders an unknown uid as plain text, not a chip', () => {
    const { container } = render(createElement(CommentBody, { body: 'hi @[Jake Fear](user:nope)', mentionNames: {} }));
    expect(container.querySelector('.comment-mention-chip')).toBeNull();
    expect(container.textContent).toContain('@Jake Fear');
  });
});
