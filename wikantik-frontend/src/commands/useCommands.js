import { useCallback, useEffect, useSyncExternalStore } from 'react';
import { registerCommands, getCommands, subscribe, runCommand } from './registry';
import { useToast } from '../hooks/useToast';

/** Registers {@code list} for as long as the calling component is mounted (re-registers when deps change). */
export function useRegisterCommands(list, deps) {
  // eslint-disable-next-line react-hooks/exhaustive-deps -- caller-supplied deps, like useMemo
  useEffect(() => registerCommands(list), deps);
}

export function useCommands() {
  return useSyncExternalStore(subscribe, getCommands, getCommands);
}

export function useRunCommand() {
  const toast = useToast();
  return useCallback((id) => runCommand(id, {
    onError: (cmd) => toast.error(`Command failed: ${cmd.title}`),
  }), [toast]);
}
