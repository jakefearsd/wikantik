/**
 * The member rows of a project that may be shown as people to assign, hand off to, filter by or @-mention.
 * A reporter row is never one of them: assigning a reporter is refused and a mention of one stores nothing, and
 * listing them would put a name in front of the viewer that no action can use.
 */
export const assignable = (members) => (members || []).filter((m) => m && m.role !== 'reporter');
