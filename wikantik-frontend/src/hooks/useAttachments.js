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
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const fetchList = useCallback(async () => {
    if (!pageName) return;
    setLoading(true);
    setError(null);
    try {
      const data = await api.listAttachments(pageName);
      const attachments = (data.attachments || []).map(att => ({
        ...att,
        isImage: isImageFile(att.fileName),
      }));
      setList(attachments);
    } catch (err) {
      setError(err.message || 'Failed to load attachments');
    } finally {
      setLoading(false);
    }
  }, [pageName]);

  useEffect(() => { fetchList(); }, [fetchList]);

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
