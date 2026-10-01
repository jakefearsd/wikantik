import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import NewArticleModal from '../components/NewArticleModal';
import { useRegisterCommands } from '../commands/useCommands';

const NewPageContext = createContext({ openNewPage: () => {} });

export function NewPageProvider({ children }) {
  const [state, setState] = useState(null); // { initialTitle }
  const openNewPage = useCallback((initialTitle = '') => setState({ initialTitle }), []);
  const commands = useMemo(() => [
    { id: 'new-page', title: 'New page', section: 'Page', run: () => openNewPage('') },
  ], [openNewPage]);
  useRegisterCommands(commands, [commands]);
  const value = useMemo(() => ({ openNewPage }), [openNewPage]);
  return (
    <NewPageContext.Provider value={value}>
      {children}
      {state && <NewArticleModal isOpen initialTitle={state.initialTitle} onClose={() => setState(null)} />}
    </NewPageContext.Provider>
  );
}

export const useNewPage = () => useContext(NewPageContext);
