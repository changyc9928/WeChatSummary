import { useCallback, useEffect, useRef, useState } from 'react';
import { apiClient } from '../api/client';

export default function usePreprocess({ uuidInput, currentUser, onCompleted }) {
  const [isFinished, setIsFinished] = useState(false);
  const [progress, setProgress] = useState(null);
  const [loading, setLoading] = useState(false);
  const [aborting, setAborting] = useState(false);
  const [reprocessing, setReprocessing] = useState(false);
  const [restarting, setRestarting] = useState(false);
  const [error, setError] = useState(null);

  const pollRef = useRef(null);
  const onCompletedRef = useRef(onCompleted);
  useEffect(() => {
    onCompletedRef.current = onCompleted;
  }, [onCompleted]);

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  const checkProgress = useCallback(async (uuid) => {
    try {
      const data = await apiClient.preprocess.getProgress({
        xUserId: currentUser?.uuid,
        uuid
      });
      if (data?.data) {
        setProgress(data.data);
        return data.data;
      }
    } catch (err) {
      // A single failed poll (laptop sleep, brief network blip) must not end the session: the
      // interval keeps running and the next tick re-establishes the true state. Surfacing the
      // error here used to be indistinguishable from a finished task and left the UI frozen
      // until the user manually refreshed the page.
      console.error(err);
    }
    return null;
  }, [currentUser]);

  const startPolling = useCallback((uuid) => {
    stopPolling();
    const check = async () => {
      const data = await checkProgress(uuid);
      if (!data) return;

      const isFinishedNow = data.status === 'COMPLETED';
      if (isFinishedNow) {
        stopPolling();
        setIsFinished(true);
        setProgress(data);
        onCompletedRef.current?.(uuid);
      }
      // NOTE: deliberately no longer stop on PAUSED. An aborted run is still observable, and the
      // watchdog may close a stalled run out on its own, so polling must continue to observe the
      // transition to COMPLETED instead of requiring a page refresh.
    };
    check();
    pollRef.current = setInterval(check, 1500);
  }, [checkProgress, stopPolling]);

  const startPreprocess = useCallback(async () => {
    if (!uuidInput || !currentUser) return;
    setError(null);
    setLoading(true);
    try {
      await apiClient.preprocess.preprocess({ xUserId: currentUser.uuid, uuid: uuidInput });
      startPolling(uuidInput);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [uuidInput, currentUser, startPolling]);

  const abortPreprocess = useCallback(async () => {
    if (!uuidInput) return;
    setAborting(true);
    try {
      await apiClient.preprocess.abortTask({ xUserId: currentUser?.uuid, uuid: uuidInput });
      stopPolling();
      await checkProgress(uuidInput);
    } catch (err) {
      setError(err.message);
    } finally {
      setAborting(false);
    }
  }, [uuidInput, currentUser, stopPolling, checkProgress]);

  const reprocess = useCallback(async () => {
    if (!uuidInput || !currentUser) return;
    setError(null);
    setReprocessing(true);
    try {
      await apiClient.preprocess.reprocess({ xUserId: currentUser.uuid, uuid: uuidInput });
      setIsFinished(true);
      await checkProgress(uuidInput);
    } catch (err) {
      setError(err.message);
    } finally {
      setReprocessing(false);
    }
  }, [uuidInput, currentUser, checkProgress]);

  const restartPreprocess = useCallback(async () => {
    if (!uuidInput || !currentUser) return;
    setError(null);
    setRestarting(true);
    setIsFinished(false);
    try {
      await apiClient.preprocess.restartPreprocess({ xUserId: currentUser.uuid, uuid: uuidInput });
      startPolling(uuidInput);
    } catch (err) {
      setError(err.message);
    } finally {
      setRestarting(false);
    }
  }, [uuidInput, currentUser, startPolling]);

  useEffect(() => {
    if (uuidInput && currentUser) {
      setProgress(null);
      setIsFinished(false);

      const initializeSessionStatus = async () => {
        const progressData = await checkProgress(uuidInput);
        if (!progressData) return;
        if (progressData.status === 'COMPLETED') {
          setIsFinished(true);
          setProgress(progressData);
          onCompletedRef.current?.(uuidInput);
          return;
        }
        // RUNNING and PAUSED both keep polling. This is what lets the page recover on its own after
        // a reload: the watchdog can close a stalled run out server-side, and the open poll picks up
        // the transition to COMPLETED instead of the user having to refresh again. IDLING is
        // deliberately not polled - there is no task to watch and it would poll forever.
        if (progressData.status === 'RUNNING' || progressData.status === 'PAUSED') {
          startPolling(uuidInput);
        }
      };
      initializeSessionStatus();
    } else {
      setIsFinished(false);
      setProgress(null);
      stopPolling();
    }
    return stopPolling;
  }, [uuidInput, currentUser, checkProgress, startPolling, stopPolling]);

  return {
    isFinished,
    progress,
    loading,
    aborting,
    reprocessing,
    restarting,
    error,
    startPreprocess,
    abortPreprocess,
    reprocess,
    restartPreprocess,
    checkProgress
  };
}