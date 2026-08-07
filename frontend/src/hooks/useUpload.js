import { useCallback, useState } from 'react';
import * as uploadApi from '../api/uploadApi';

export default function useUpload({ currentUser, onUploaded }) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const handleUpload = useCallback(async (e, file) => {
    e.preventDefault();
    if (!file) return alert('Please select a file first!');
    if (!currentUser) return;

    setLoading(true);
    setError(null);
    try {
      const assignedUuid = await uploadApi.uploadFile(file, currentUser.uuid);
      if (assignedUuid) onUploaded?.(assignedUuid.trim());
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [currentUser, onUploaded]);

  return { loading, error, handleUpload };
}