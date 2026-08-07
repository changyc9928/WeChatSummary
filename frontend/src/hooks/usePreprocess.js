import { useCallback, useEffect, useRef, useState } from 'react';
import * as preprocessApi from '../api/preprocessApi';

export default function usePreprocess({ uuidInput, currentUser, onCompleted }) {
  const [isFinished, setIsFinished] = useState(false);
  const [progress, setProgress] = useState(null);
  const [loading, setLoading] = useState(false);
  const [aborting, setAborting] = useState(false);
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
      const data = await preprocessApi.getPreprocessProgress(uuid, currentUser?.uuid);
      if (data) {
        setProgress(data);
        return data;
      }
    } catch (err) {
      console.error(err);
    }
    return null;
  }, [currentUser]);

  const startPolling = useCallback((uuid) => {
    stopPolling();
    const check = async () => {
      const data = await checkProgress(uuid);
      if (!data) return;

      const isFinishedNow = data.status === 'COMPLETED' || data.progressPercentage >= 100;
      if (isFinishedNow) {
        stopPolling();
        setIsFinished(true);
        setProgress(data);
        onCompletedRef.current?.(uuid);
      } else if (data.status === 'PAUSED') {
        stopPolling();
      }
    };
    check();
    pollRef.current = setInterval(check, 1500);
  }, [checkProgress, stopPolling]);

  const startPreprocess = useCallback(async () => {
    if (!uuidInput || !currentUser) return;
    setError(null);
    setLoading(true);
    try {
      await preprocessApi.startPreprocess(uuidInput, currentUser.uuid);
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
      await preprocessApi.abortPreprocess(uuidInput, currentUser?.uuid);
      stopPolling();
      await checkProgress(uuidInput);
    } catch (err) {
      setError(err.message);
    } finally {
      setAborting(false);
    }
  }, [uuidInput, currentUser, stopPolling, checkProgress]);

  useEffect(() => {
    if (uuidInput && currentUser) {
      setProgress(null);
      setIsFinished(false);

      const initializeSessionStatus = async () => {
        const progressData = await checkProgress(uuidInput);
        if (progressData) {
          if (progressData.status === 'RUNNING') {
            startPolling(uuidInput);
          } else if (progressData.status === 'COMPLETED') {
            setIsFinished(true);
            setProgress(progressData);
            onCompletedRef.current?.(uuidInput);
          } else if (progressData.status === 'PAUSED') {
            setProgress(progressData);
          }
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
    error,
    startPreprocess,
    abortPreprocess,
    checkProgress
  };
}