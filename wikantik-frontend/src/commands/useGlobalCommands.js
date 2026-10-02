import { useMemo } from 'react';
import { matchPath, useLocation } from 'react-router-dom';
import { useRegisterCommands } from './useCommands';
import { api } from '../api/client';
import { openDailyNote } from '../utils/dailyNote';
import { useGuardedNavigate } from '../navigation/NavigationGuardProvider';

export function useGlobalCommands({ openOverlay, toggleSidebar }) {
  const { pathname } = useLocation();
  const go = useGuardedNavigate();
  const viewing = matchPath('/wiki/:name', pathname)?.params.name;
  const editing = matchPath('/edit/:name', pathname)?.params.name;
  const page = viewing || editing;

  const list = useMemo(() => {
    const cmds = [
      { id: 'go-to-page', title: 'Go to page', section: 'Navigate', keys: 'Mod-O', run: () => openOverlay('pages') },
      { id: 'search-full-text', title: 'Search full text', section: 'Navigate', run: () => go('/search') },
      { id: 'recent-changes', title: 'Recent changes', section: 'Navigate', run: () => go('/wiki/RecentChanges') },
      { id: 'daily-note', title: "Open today's daily note", section: 'Page', keys: 'Mod-Alt-N', keywords: ['journal', 'today'], run: () => openDailyNote({ api, go }) },
      { id: 'toggle-sidebar', title: 'Toggle sidebar', section: 'View', run: () => toggleSidebar() },
    ];
    if (viewing) cmds.push({ id: 'edit-page', title: 'Edit this page', section: 'Page', run: () => go(`/edit/${viewing}`) });
    if (page) cmds.push({ id: 'page-history', title: 'Page history', section: 'Page', run: () => go(`/diff/${page}`) });
    return cmds;
  }, [openOverlay, toggleSidebar, go, viewing, page]);

  useRegisterCommands(list, [list]);
}
