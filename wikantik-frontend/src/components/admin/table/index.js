/**
 * Admin table primitives with built-in multi-select + bulk-action pattern.
 *
 * @module components/admin/table
 */

export { default as AdminTable } from './AdminTable';
export { useTableSelection } from './useTableSelection';
export { default as SelectionBar } from './SelectionBar';
export { default as BulkActionMenu } from './BulkActionMenu';
export { default as ConfirmBulkModal } from './ConfirmBulkModal';

/**
 * @typedef {{
 *   id: string,
 *   label: string,
 *   variant?: 'default' | 'primary' | 'danger',
 *   confirm?: boolean | { title: string, body: (rows: any[]) => any, confirmLabel?: string },
 *   reason?: { label: string, placeholder?: string, required?: boolean },
 *   disabled?: (selected: any[]) => false | string,
 * }} BulkAction
 */

/**
 * @typedef {{
 *   succeeded: string[],
 *   failed: Array<{ id: string, error: string }>,
 *   status: 'completed' | 'pending',
 *   jobId?: string,
 *   message?: string,
 * }} BulkResult
 */
