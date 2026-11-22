import { createContext, useContext } from 'react';

/** Carries the loaded project and the viewer's role from WorkLayout to the views below it. */
export const WorkProjectContext = createContext(null);

const EMPTY = { project: null, role: null, reload: () => {}, sprints: [], reloadSprints: () => {}, sprintsSettled: true };

/** { project, role, reload, sprints, reloadSprints } for the current /work/:project route. role is viewer | member | lead. */
export function useWorkProject() {
  return useContext(WorkProjectContext) || EMPTY;
}
