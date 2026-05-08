import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, beforeEach } from 'vitest';
import { useTableSelection } from './useTableSelection';

describe('useTableSelection', () => {
  let result;

  beforeEach(() => {
    ({ result } = renderHook(() => useTableSelection()));
  });

  // toggle adds a key
  it('toggle adds a key when not selected', () => {
    act(() => result.current.toggle('a'));
    expect(result.current.selected.has('a')).toBe(true);
  });

  // toggle removes a key
  it('toggle removes a key when already selected', () => {
    act(() => result.current.toggle('a'));
    act(() => result.current.toggle('a'));
    expect(result.current.selected.has('a')).toBe(false);
  });

  // toggleAll selects all when none selected
  it('toggleAll selects all keys when none are selected', () => {
    act(() => result.current.toggleAll(['a', 'b', 'c']));
    expect([...result.current.selected]).toEqual(expect.arrayContaining(['a', 'b', 'c']));
    expect(result.current.selected.size).toBe(3);
  });

  // toggleAll selects all when some selected
  it('toggleAll selects all keys when some are already selected', () => {
    act(() => result.current.toggle('a'));
    act(() => result.current.toggleAll(['a', 'b', 'c']));
    expect(result.current.selected.size).toBe(3);
  });

  // toggleAll deselects all when all selected
  it('toggleAll deselects all when all are selected', () => {
    act(() => result.current.toggleAll(['a', 'b', 'c']));
    act(() => result.current.toggleAll(['a', 'b', 'c']));
    expect(result.current.selected.size).toBe(0);
  });

  // toggleAll on empty keys is no-op
  it('toggleAll on empty keys array does nothing', () => {
    act(() => result.current.toggle('x'));
    act(() => result.current.toggleAll([]));
    expect(result.current.selected.has('x')).toBe(true);
    expect(result.current.selected.size).toBe(1);
  });

  // isIndeterminate true iff 0 < selected < total
  it('isIndeterminate returns true when some but not all are selected', () => {
    act(() => result.current.toggle('a'));
    expect(result.current.isIndeterminate(['a', 'b', 'c'])).toBe(true);
  });

  it('isIndeterminate returns false when none are selected', () => {
    expect(result.current.isIndeterminate(['a', 'b', 'c'])).toBe(false);
  });

  it('isIndeterminate returns false when all are selected', () => {
    act(() => result.current.toggleAll(['a', 'b', 'c']));
    expect(result.current.isIndeterminate(['a', 'b', 'c'])).toBe(false);
  });

  it('isIndeterminate returns false for empty keys', () => {
    expect(result.current.isIndeterminate([])).toBe(false);
  });

  // clear empties the set
  it('clear empties the selection', () => {
    act(() => result.current.toggleAll(['a', 'b', 'c']));
    act(() => result.current.clear());
    expect(result.current.selected.size).toBe(0);
  });

  // isAllSelected
  it('isAllSelected returns true only when all keys are selected', () => {
    act(() => result.current.toggleAll(['a', 'b']));
    expect(result.current.isAllSelected(['a', 'b'])).toBe(true);
    expect(result.current.isAllSelected(['a', 'b', 'c'])).toBe(false);
  });

  it('isAllSelected returns false for empty keys', () => {
    expect(result.current.isAllSelected([])).toBe(false);
  });

  // isSelected
  it('isSelected reflects current selection', () => {
    act(() => result.current.toggle('x'));
    expect(result.current.isSelected('x')).toBe(true);
    expect(result.current.isSelected('y')).toBe(false);
  });

  // shift-click selects a contiguous range (forward)
  it('shift-click selects contiguous range in forward direction', () => {
    const allKeys = ['a', 'b', 'c', 'd', 'e'];
    act(() => result.current.toggle('b', { allKeys }));          // anchor = b
    act(() => result.current.toggle('d', { shift: true, allKeys })); // range b..d
    expect([...result.current.selected]).toEqual(expect.arrayContaining(['b', 'c', 'd']));
    expect(result.current.selected.size).toBe(3);
  });

  // shift-click selects a contiguous range (reverse)
  it('shift-click selects contiguous range in reverse direction', () => {
    const allKeys = ['a', 'b', 'c', 'd', 'e'];
    act(() => result.current.toggle('d', { allKeys }));           // anchor = d
    act(() => result.current.toggle('b', { shift: true, allKeys })); // range b..d
    expect([...result.current.selected]).toEqual(expect.arrayContaining(['b', 'c', 'd']));
    expect(result.current.selected.size).toBe(3);
  });

  // shift-click without anchor falls back to single toggle
  it('shift-click without anchor falls back to single toggle', () => {
    const allKeys = ['a', 'b', 'c'];
    // No prior toggle to set anchor — clear resets it
    act(() => result.current.clear());
    act(() => result.current.toggle('b', { shift: true, allKeys }));
    // Should just toggle 'b' as a single item (since anchor = null)
    expect(result.current.selected.has('b')).toBe(true);
    expect(result.current.selected.size).toBe(1);
  });

  // shift-click when anchor not in allKeys falls back to single toggle
  it('shift-click falls back to single-toggle when anchor is missing from allKeys', () => {
    const allKeys = ['a', 'b', 'c'];
    act(() => result.current.toggle('z', { allKeys: ['z'] }));    // anchor = z (not in new allKeys)
    act(() => result.current.toggle('b', { shift: true, allKeys }));
    // 'z' stays (prior toggle), 'b' is toggled as single
    expect(result.current.selected.has('b')).toBe(true);
  });

  // shift-click does not unselect the anchor
  it('shift-click range includes the anchor key', () => {
    const allKeys = ['a', 'b', 'c', 'd'];
    act(() => result.current.toggle('a', { allKeys }));
    act(() => result.current.toggle('c', { shift: true, allKeys }));
    expect(result.current.selected.has('a')).toBe(true);
    expect(result.current.selected.has('b')).toBe(true);
    expect(result.current.selected.has('c')).toBe(true);
  });

  // multiple independent toggleAll calls accumulate correctly
  it('selection is independent of insertion order (Set semantics)', () => {
    act(() => result.current.toggle('c'));
    act(() => result.current.toggle('a'));
    act(() => result.current.toggle('b'));
    expect(result.current.selected.size).toBe(3);
    expect(result.current.isAllSelected(['a', 'b', 'c'])).toBe(true);
  });
});
