import Select from '../ui/Select';
import { TYPE_OPTIONS } from './boardModel';

/** The type and label filters shared by the board and the dates page. Presentational: the page loads the labels. */
export default function WorkTypeLabelFilters({ type, label, labels = [], onType, onLabel }) {
  return (
    <>
      <Select ariaLabel="Filter by type" placeholder="All types" value={type} options={TYPE_OPTIONS} onChange={onType} />
      <Select ariaLabel="Filter by label" placeholder="All labels" value={label}
        options={labels.map((l) => ({ value: l.name, label: l.count == null ? l.name : `${l.name} (${l.count})` }))}
        onChange={onLabel} />
    </>
  );
}
