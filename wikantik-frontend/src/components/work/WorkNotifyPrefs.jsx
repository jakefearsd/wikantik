import { useCallback, useEffect, useRef, useState } from 'react';
import { getNotifyPrefs, setNotifyPrefs } from '../../api/work';
import { useToast } from '../../hooks/useToast';

const CHOICES = [
  { value: 'each', label: 'Each update, batched' },
  { value: 'daily', label: 'Once a day' },
  { value: 'off', label: 'Off' },
];

/** The viewer's email choice for work notifications. Without a mail server the choice is not offered at all. */
export default function WorkNotifyPrefs() {
  const toast = useToast();
  const [state, setState] = useState({ prefs: null, error: null });
  const [busy, setBusy] = useState(false);
  const seq = useRef(0);

  const load = useCallback(() => {
    const mine = ++seq.current;
    return getNotifyPrefs()
      .then((prefs) => { if (mine === seq.current) setState({ prefs, error: null }); })
      .catch((e) => {
        console.warn('WorkNotifyPrefs: could not load the email choice', e);
        // A failed reload keeps the last good choice.
        if (mine === seq.current) setState((s) => ({ prefs: s.prefs, error: e?.message || 'Could not load your email choice.' }));
      });
  }, []);
  useEffect(() => { load(); }, [load]);

  function choose(email) {
    setBusy(true);
    setNotifyPrefs(email)
      .then((prefs) => { setState({ prefs, error: null }); toast.success('Email choice saved'); })
      .catch((e) => {
        console.warn('WorkNotifyPrefs: could not save the email choice', e);
        toast.error(e?.message || 'Could not save your email choice.');
      })
      .finally(() => setBusy(false));
  }

  const { prefs, error } = state;
  if (!prefs) {
    return error ? (
      <div className="work-form-error" role="alert">
        {error} <button type="button" className="btn btn-sm" onClick={load}>Retry</button>
      </div>
    ) : null;
  }
  if (!prefs.emailAvailable) return <p className="work-muted">Email notifications are off on this wiki.</p>;
  return (
    <fieldset className="work-notify-prefs" disabled={busy}>
      <legend className="work-subheading">Email</legend>
      {CHOICES.map((c) => (
        <label key={c.value} className="work-notify-choice">
          <input type="radio" name="work-notify-email" value={c.value} checked={prefs.email === c.value}
            onChange={() => choose(c.value)} /> {c.label}
        </label>
      ))}
      {error && (
        <div className="work-form-error" role="alert">
          {error} <button type="button" className="btn btn-sm" onClick={load}>Retry</button>
        </div>
      )}
    </fieldset>
  );
}
