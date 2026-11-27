import { createQueue } from '../../api/work';

const STARTERS = [
  { key: 'implementer', name: 'Implementer', description: 'Ready work waiting for an agent to implement.' },
  { key: 'review', name: 'Review', description: 'Completed work waiting for review.' },
];

/**
 * Creates the starter queues whose keys are not in `existingKeys` (retired queues count as existing).
 * Never throws: a failed create is logged and reported in `failed`.
 */
export async function createStarterQueues(existingKeys) {
  const have = new Set(existingKeys || []);
  const created = [];
  const failed = [];
  for (const q of STARTERS) {
    if (have.has(q.key)) continue;
    try {
      await createQueue(q);
      created.push(q.key);
    } catch (err) {
      console.warn(`starterQueues: could not create queue ${q.key}`, err);
      failed.push(q.key);
    }
  }
  return { created, failed };
}
