import { Outlet } from 'react-router-dom';
import EmptyState from '../ui/EmptyState';
import Spinner from '../ui/Spinner';
import { useCapabilities } from '../../hooks/useCapabilities';
import '../../styles/work.css';

/** Parent of every /work route: renders them only when the backend reports work management on. */
export default function WorkGate() {
  const { capabilities, loading } = useCapabilities();
  if (loading) return <Spinner label="Loading…" />;
  if (!capabilities?.work) return <EmptyState message="Work management is not enabled" />;
  return <div className="work-shell"><Outlet /></div>;
}
