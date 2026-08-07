import { useCallback, useEffect, useRef, useState } from 'react';
import { apiClient } from '../api/client';

const INITIAL_STATE = { status: 'INITIAL_STATE', progress: 0.0, result: null, errorMessage: null };

export default function useSummaryStatus({ uuidInput, currentUser }) {
  const [summaryState, setSummaryState] = useState(INITIAL_STATE);
  const [loading, setLoading] = useState(false);
  const [pausing, setPausing] = useState(false);
  const [restarting, setRestarting] = useState(false);
  const [error, setError] = useState(null);

  const pollRef = useRef(null);
  const stateRef = useRef(summaryState);
  useEffect(() => {
    stateRef.current = summaryState;
  }, [summaryState]);

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  const fetchStatus = useCallback(async (uuid) => {
    try {
      const data = await apiClient.chatSummary.getStatusAndProgress({
        xUserId: currentUser?.uuid,
        uuid
      });
      if (!data) return;

      const payload = data.data || {};
      const status = (payload.status || 'INITIAL_STATE').toUpperCase();
      let rawProgress = payload.progress ?? payload.progressPercentage ?? 0;
      const progress = rawProgress > 1 ? rawProgress / 100 : rawProgress;

      const result = payload.result || stateRef.current.result || null;

      setSummaryState(prevState => ({
        ...prevState,
        status: status,
        progress: progress,
        result: result,
        errorMessage: payload.errorMessage || prevState.errorMessage || null
      }));

      if (status === 'RUNNING' && !pollRef.current) {
        pollRef.current = setInterval(() => fetchStatus(uuid), 2000);
      } else if (status !== 'RUNNING') {
        stopPolling();
      }
    } catch (err) {
      console.error("Error fetching summary status:", err);
    }
  }, [currentUser, stopPolling]);

  const startSummary = useCallback(async (payload = {}) => {
    if (!uuidInput) return;
    setLoading(true);
    setError(null);
    try {
      await apiClient.chatSummary.startSummary({
        xUserId: currentUser?.uuid,
        uuid: uuidInput,
        summaryRequestDTO: payload
      });
      fetchStatus(uuidInput);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [uuidInput, currentUser, fetchStatus]);

  const restartSummary = useCallback(async (payload = {}) => {
    if (!uuidInput) return;
    setRestarting(true);
    try {
      await apiClient.chatSummary.restartSummary({
        xUserId: currentUser?.uuid,
        uuid: uuidInput,
        summaryRequestDTO: payload
      });
      fetchStatus(uuidInput);
    } catch (err) {
      console.error(err);
    } finally {
      setRestarting(false);
    }
  }, [uuidInput, currentUser, fetchStatus]);

  const pauseSummary = useCallback(async () => {
    if (!uuidInput) return;
    setPausing(true);
    try {
      await apiClient.chatSummary.pauseSummary({
        xUserId: currentUser?.uuid,
        uuid: uuidInput
      });
      fetchStatus(uuidInput);
    } catch (err) {
      console.error(err);
    } finally {
      setPausing(false);
    }
  }, [uuidInput, currentUser, fetchStatus]);

  useEffect(() => {
    setSummaryState(INITIAL_STATE);
    stopPolling();
  }, [uuidInput, stopPolling]);

  useEffect(() => () => stopPolling(), [stopPolling]);

  return {
    summaryState,
    loading,
    pausing,
    restarting,
    error,
    fetchStatus,
    startSummary,
    restartSummary,
    pauseSummary
  };
}