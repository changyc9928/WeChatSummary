import { useCallback, useEffect, useRef, useState } from 'react';
import { apiClient } from '../api/client';

const INITIAL_STATE = { status: 'INITIAL_STATE', progress: 0.0, result: null, errorMessage: null };

export default function useSummaryStatus({ uuidInput }) {
  const [summaryState, setSummaryState] = useState(INITIAL_STATE);
  const [loading, setLoading] = useState(false);
  const [pausing, setPausing] = useState(false);
  const [restarting, setRestarting] = useState(false);
  const [error, setError] = useState(null);

  const pollRef = useRef(null);
  const stateRef = useRef(summaryState);
  const pendingStartRef = useRef(false);
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
        uuid
      });
      if (!data) return;

      const payload = data.data || {};
      const status = (payload.status || 'INITIAL_STATE').toUpperCase();
      let rawProgress = payload.progress ?? payload.progressPercentage ?? 0;
      const progress = rawProgress > 1 ? rawProgress / 100 : rawProgress;

      const result = payload.result || stateRef.current.result || null;

      if (!(pendingStartRef.current && status === 'INITIAL_STATE')) {
        setSummaryState(prevState => ({
          ...prevState,
          status: status,
          progress: progress,
          result: result,
          errorMessage: payload.errorMessage || prevState.errorMessage || null
        }));
      }

      if (status === 'RUNNING') {
        pendingStartRef.current = false;
        if (!pollRef.current) {
          pollRef.current = setInterval(() => fetchStatus(uuid), 2000);
        }
      } else if (pendingStartRef.current && status === 'INITIAL_STATE') {
        if (!pollRef.current) {
          pollRef.current = setInterval(() => fetchStatus(uuid), 2000);
        }
      } else {
        pendingStartRef.current = false;
        stopPolling();
      }
    } catch (err) {
      console.error("Error fetching summary status:", err);
    }
  }, [stopPolling]);

  const startSummary = useCallback(async (payload = {}) => {
    if (!uuidInput) return;
    setLoading(true);
    setError(null);
    pendingStartRef.current = true;
    setSummaryState({ status: 'RUNNING', progress: 0, result: null, errorMessage: null });
    try {
      await apiClient.chatSummary.startSummary({
        uuid: uuidInput,
        summaryRequestDTO: payload
      });
      fetchStatus(uuidInput);
    } catch (err) {
      setError(err.message);
      pendingStartRef.current = false;
      stopPolling();
      setSummaryState(INITIAL_STATE);
    } finally {
      setLoading(false);
    }
  }, [uuidInput, fetchStatus, stopPolling]);

  const restartSummary = useCallback(async (payload = {}) => {
    if (!uuidInput) return;
    setRestarting(true);
    pendingStartRef.current = true;
    setSummaryState({ status: 'RUNNING', progress: 0, result: null, errorMessage: null });
    try {
      await apiClient.chatSummary.restartSummary({
        uuid: uuidInput,
        summaryRequestDTO: payload
      });
      fetchStatus(uuidInput);
    } catch (err) {
      console.error(err);
      pendingStartRef.current = false;
      stopPolling();
      setSummaryState(INITIAL_STATE);
    } finally {
      setRestarting(false);
    }
  }, [uuidInput, fetchStatus, stopPolling]);

  const pauseSummary = useCallback(async () => {
    if (!uuidInput) return;
    setPausing(true);
    try {
      await apiClient.chatSummary.pauseSummary({
        uuid: uuidInput
      });
      fetchStatus(uuidInput);
    } catch (err) {
      console.error(err);
    } finally {
      setPausing(false);
    }
  }, [uuidInput, fetchStatus]);

  useEffect(() => {
    setSummaryState(INITIAL_STATE);
    pendingStartRef.current = false;
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