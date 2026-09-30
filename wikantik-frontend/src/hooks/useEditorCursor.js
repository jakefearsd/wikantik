import { useSyncExternalStore } from 'react';

export function useEditorCursor(store) {
  return useSyncExternalStore(store.subscribe, store.get);
}
