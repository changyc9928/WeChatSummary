import { useCallback, useEffect, useState } from 'react';
import { apiClient } from '../api/client';
import { INITIAL_PAGINATION } from './useImageSummaries';

export default function useAudioSummaries({ uuidInput, currentUser }) {
  const [audioSummaries, setAudioSummaries] = useState([]);
  const [selectedAudioIds, setSelectedAudioIds] = useState([]);
  const [loadingAudios, setLoadingAudios] = useState(false);
  const [audioPagination, setAudioPagination] = useState(INITIAL_PAGINATION);
  const [deleting, setDeleting] = useState(false);
  const [clearingText, setClearingText] = useState(false);
  const [batchDeleting, setBatchDeleting] = useState(false);
  const [batchClearingText, setBatchClearingText] = useState(false);
  const [error, setError] = useState(null);

  const fetchAudioSummaries = useCallback(async (sessionUuid, page = 0, size = 20) => {
    if (!sessionUuid || !currentUser) {
      setAudioSummaries([]);
      return;
    }
    setLoadingAudios(true);
    try {
      const data = await apiClient.preprocess.getAudioSummariesByUuid({
        xUserId: currentUser.uuid,
        uuid: sessionUuid,
        page,
        size
      });
      setAudioSummaries(data.content || []);
      setAudioPagination({
        page: data.number,
        size: data.size,
        totalPages: data.totalPages,
        totalElements: data.totalElements,
        isFirst: data.first,
        isLast: data.last
      });
    } catch (err) {
      setError(err.message);
    } finally {
      setLoadingAudios(false);
    }
  }, [currentUser]);

  useEffect(() => {
    if (uuidInput && currentUser) {
      fetchAudioSummaries(uuidInput, 0, INITIAL_PAGINATION.size);
    } else {
      setAudioSummaries([]);
      setSelectedAudioIds([]);
      setAudioPagination(INITIAL_PAGINATION);
    }
  }, [uuidInput, currentUser, fetchAudioSummaries]);

  const deleteAudio = useCallback(async (id) => {
    if (!currentUser) return;
    setDeleting(true);
    try {
      await apiClient.preprocess.deleteAudioSummaryById({
        xUserId: currentUser.uuid,
        id
      });
      setSelectedAudioIds(prev => prev.filter(item => item !== id));
      await fetchAudioSummaries(uuidInput, audioPagination.page, audioPagination.size);
    } catch (err) {
      setError(err.message);
    } finally {
      setDeleting(false);
    }
  }, [currentUser, uuidInput, audioPagination, fetchAudioSummaries]);

  const clearAudioText = useCallback(async (id) => {
    if (!currentUser) return;
    setClearingText(true);
    try {
      await apiClient.preprocess.clearAudioSummaryTextById({
        xUserId: currentUser.uuid,
        id
      });
      await fetchAudioSummaries(uuidInput, audioPagination.page, audioPagination.size);
    } catch (err) {
      setError(err.message);
    } finally {
      setClearingText(false);
    }
  }, [currentUser, uuidInput, audioPagination, fetchAudioSummaries]);

  const batchDeleteAudios = useCallback(async (ids = selectedAudioIds) => {
    if (!currentUser || ids.length === 0) return;
    setBatchDeleting(true);
    try {
      await apiClient.preprocess.deleteAudioSummariesByIds({
        xUserId: currentUser.uuid,
        requestBody: ids
      });
      setSelectedAudioIds([]);
      await fetchAudioSummaries(uuidInput, 0, audioPagination.size);
    } catch (err) {
      setError(err.message);
    } finally {
      setBatchDeleting(false);
    }
  }, [currentUser, selectedAudioIds, uuidInput, audioPagination.size, fetchAudioSummaries]);

  const batchClearAudioTexts = useCallback(async (ids = selectedAudioIds) => {
    if (!currentUser || ids.length === 0) return;
    setBatchClearingText(true);
    try {
      await apiClient.preprocess.clearAudioSummaryTextsByIds({
        xUserId: currentUser.uuid,
        requestBody: ids
      });
      setSelectedAudioIds([]);
      await fetchAudioSummaries(uuidInput, 0, audioPagination.size);
    } catch (err) {
      setError(err.message);
    } finally {
      setBatchClearingText(false);
    }
  }, [currentUser, selectedAudioIds, uuidInput, audioPagination.size, fetchAudioSummaries]);

  return {
    audioSummaries,
    selectedAudioIds,
    setSelectedAudioIds,
    loadingAudios,
    audioPagination,
    deleting,
    clearingText,
    batchDeleting,
    batchClearingText,
    error,
    fetchAudioSummaries,
    deleteAudio,
    clearAudioText,
    batchDeleteAudios,
    batchClearAudioTexts
  };
}