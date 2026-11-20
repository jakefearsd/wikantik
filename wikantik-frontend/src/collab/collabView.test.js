import { describe, it, expect } from 'vitest';
import { collabView, COLLAB_FALLBACK, COLLAB_EDITING } from './collabView';

// The oracle: the flag expressions PageEditor wrote by hand before collabView() existed.
function oracle(status, extensions, wanted) {
  const collab = { status, extensions };
  const collabMode = wanted && !COLLAB_FALLBACK.has(collab.status);
  const collabEditing = collabMode && !!collab.extensions && COLLAB_EDITING.has(collab.status);
  const collabForbidden = collabMode && collab.status === 'forbidden' && !collab.extensions;
  const collabEvicted = collabEditing && collab.status === 'forbidden';
  const collabFrozen = collabEditing && collab.status === 'frozen';
  const collabReadOnly = collabEditing && (collabFrozen || collab.status === 'resync' || collabEvicted);
  return {
    mode: collabMode, editing: collabEditing, forbidden: collabForbidden, evicted: collabEvicted,
    frozen: collabFrozen, readOnly: collabReadOnly, locked: collabReadOnly || collabForbidden,
  };
}

const STATUSES = [...COLLAB_FALLBACK, ...COLLAB_EDITING, 'joining', undefined];

describe('collabView', () => {
  it('names both status sets as before', () => {
    expect([...COLLAB_FALLBACK]).toEqual(['disabled', 'unavailable', 'full', 'error']);
    expect([...COLLAB_EDITING]).toEqual(['live', 'reconnecting', 'resync', 'frozen', 'auth-required', 'forbidden']);
  });

  for (const status of STATUSES) {
    for (const extensions of [{}, undefined]) {
      for (const wanted of [true, false]) {
        it(`status=${status} extensions=${extensions ? 'present' : 'absent'} wanted=${wanted}`, () => {
          expect(collabView(status, extensions, wanted)).toEqual(oracle(status, extensions, wanted));
        });
      }
    }
  }
});
