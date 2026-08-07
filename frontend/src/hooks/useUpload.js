import { useCallback, useState } from 'react';
import { apiClient } from '../api/client';

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
      const response = await apiClient.upload.upload({
        xUserId: currentUser.uuid,
        file
      });
      const assignedUuid = response?.data?.sessionId;
      if (assignedUuid) onUploaded?.(assignedUuid.trim());
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [currentUser, onUploaded]);

  return { loading, error, handleUpload };
}