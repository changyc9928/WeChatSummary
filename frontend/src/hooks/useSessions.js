import { useCallback, useEffect, useState } from 'react';
import { apiClient } from '../api/client';

export default function useSessions(currentUser) {
  const [sessions, setSessions] = useState([]);
  const [loadingSessions, setLoadingSessions] = useState(false);
  const [error, setError] = useState(null);

  const fetchSessions = useCallback(async () => {
    if (!currentUser) return;
    setLoadingSessions(true);
    try {
      const data = await apiClient.upload.getAvailableSessions({
        xUserId: currentUser.uuid
      });
      setSessions(data?.data || []);
      setError(null);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoadingSessions(false);
    }
  }, [currentUser]);

  useEffect(() => {
    if (currentUser) {
      fetchSessions();
    } else {
      setSessions([]);
    }
  }, [currentUser, fetchSessions]);

  return { sessions, loadingSessions, error, fetchSessions };
}