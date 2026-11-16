// An archived project is read-only. Its refusals are a 409 with reason 'archived' (no current item to merge):
// a page says the project is archived and never treats such a refusal as an edit conflict.

export const ARCHIVED_TEXT = 'This project is archived';

/** True when a rejected request was refused because the project is archived. */
export const isArchivedRefusal = (e) => e?.status === 409 && (e.reason === 'archived' || e.body?.reason === 'archived');
