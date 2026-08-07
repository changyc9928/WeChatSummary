import { useCallback, useEffect, useState } from 'react';
import * as sessionApi from '../api/sessionApi';

export default function useSessions(currentUser) {
  const [sessions, setSessions] = useState([]);
  const [loadingSessions, setLoadingSessions] = useState(false);
  const [error, setError] = useState(null);

  const fetchSessions = useCallback(async () => {
    if (!currentUser) return;
    setLoadingSessions(true);
    try {
      const data = await sessionApi.getSessions(currentUser.uuid);
      setSessions(data);
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