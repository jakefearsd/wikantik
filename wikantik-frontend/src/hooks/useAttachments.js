import { useState, useEffect, useCallback } from 'react';
import { api } from '../api/client';

const IMAGE_EXTENSIONS = new Set(['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg', 'bmp']);

function isImageFile(name) {
  const dot = name.lastIndexOf('.');
  if (dot < 0) return false;
  return IMAGE_EXTENSIONS.has(name.substring(dot + 1).toLowerCase());
}

export function useAttachments(pageName) {
  const [list, setList] = useState([]);
  const [loading, setLoading] = useState(!!pageName);
  const [error, setError] = useState(null);
  // Tracks the pageName the state above reflects, so a prop change can be
  // detected and reacted to during render — resetting loading/list for the
  // new page before paint — instead of via a synchronous setState in an effect.
  const [trackedPageName, setTrackedPageName] = useState(pageName);
  if (pageName !== trackedPageName) {
    setTrackedPageName(pageName);
    setError(null);
    setLoading(!!pageName);
    if (!pageName) setList([]);
  }

  // The actual fetch: every setState here happens inside a .then/.catch/.finally
  // closure, never synchronously, so it's safe to call directly from an effect.
  const doFetch = useCallback(() => {
    if (!pageName) return Promise.resolve();
    return api.listAttachments(pageName)
      .then((data) => {
        const attachments = (data.attachments || []).map(att => ({
          ...att,
          isImage: isImageFile(att.fileName),
        }));
        setList(attachments);
        setError(null);
      })
      .catch((err) => {
        setError(err.message || 'Failed to load attachments');
      })
      .finally(() => {
        setLoading(false);
      });
  }, [pageName]);

  useEffect(() => { doFetch(); }, [doFetch]);

  // Manual reload (upload/rename/delete/reload): an event-driven call, so the
  // synchronous loading/error prelude here is fine — it's not inside an effect.
  const fetchList = useCallback(() => {
    if (!pageName) return Promise.resolve();
    setLoading(true);
    setError(null);
    return doFetch();
  }, [pageName, doFetch]);

  const uploadAttachment = useCallback(async (file, name) => {
    const data = await api.uploadAttachment(pageName, file, name);
    await fetchList();
    return data;
  }, [pageName, fetchList]);

  const renameAttachment = useCallback(async (oldName, newName) => {
    const data = await api.renameAttachment(pageName, oldName, newName);
    await fetchList();
    return { oldName, newName, data };
  }, [pageName, fetchList]);

  const deleteAttachment = useCallback(async (name) => {
    await api.deleteAttachment(pageName, name);
    await fetchList();
  }, [pageName, fetchList]);

  return { list, loading, error, uploadAttachment, renameAttachment, deleteAttachment, reload: fetchList };
}
