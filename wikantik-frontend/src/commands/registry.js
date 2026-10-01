// Single command registry. Components register contextual commands while mounted; the overlay, slash menu,
// toolbar and key bindings all read from here so they can never drift apart.
const commands = new Map();
const listeners = new Set();
let snapshot = [];

function emit() {
  snapshot = [...commands.values()];
  listeners.forEach((l) => l());
}

export function registerCommands(list) {
  list.forEach((c) => commands.set(c.id, c));
  emit();
  return () => {
    let changed = false;
    list.forEach((c) => {
      if (commands.get(c.id) === c) { commands.delete(c.id); changed = true; }
    });
    if (changed) emit();
  };
}

export const getCommands = () => snapshot;
export const getCommand = (id) => commands.get(id);

export function subscribe(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export async function runCommand(id, { onError } = {}) {
  const cmd = commands.get(id);
  if (!cmd) {
    console.warn('[commands] unknown command', id);
    return false;
  }
  try {
    await cmd.run();
    return true;
  } catch (err) {
    console.warn('[commands] command failed', id, err);
    onError?.(cmd, err);
    return false;
  }
}

export function __resetRegistryForTest() {
  commands.clear();
  emit();
}
