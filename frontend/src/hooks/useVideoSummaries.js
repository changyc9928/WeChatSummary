import { useCallback, useEffect, useState } from 'react';
import { apiClient } from '../api/client';
import { INITIAL_PAGINATION } from './useImageSummaries';

export default function useVideoSummaries({ uuidInput, currentUser }) {
  const [videoSummaries, setVideoSummaries] = useState([]);
  const [selectedVideoIds, setSelectedVideoIds] = useState([]);
  const [loadingVideos, setLoadingVideos] = useState(false);
  const [videoPagination, setVideoPagination] = useState(INITIAL_PAGINATION);
  const [deleting, setDeleting] = useState(false);
  const [clearingText, setClearingText] = useState(false);
  const [batchDeleting, setBatchDeleting] = useState(false);
  const [batchClearingText, setBatchClearingText] = useState(false);
  const [error, setError] = useState(null);

  const fetchVideoSummaries = useCallback(async (sessionUuid, page = 0, size = 20) => {
    if (!sessionUuid || !currentUser) {
      setVideoSummaries([]);
      return;
    }
    setLoadingVideos(true);
    try {
      const data = await apiClient.preprocess.getVideoSummariesByUuid({
        xUserId: currentUser.uuid,
        uuid: sessionUuid,
        page,
        size
      });
      const pageData = data?.data;
      setVideoSummaries(pageData?.content || []);
      setVideoPagination({
        page: pageData?.number,
        size: pageData?.size,
        totalPages: pageData?.totalPages,
        totalElements: pageData?.totalElements,
        isFirst: pageData?.first,
        isLast: pageData?.last
      });
    } catch (err) {
      setError(err.message);
    } finally {
      setLoadingVideos(false);
    }
  }, [currentUser]);

  useEffect(() => {
    if (uuidInput && currentUser) {
      fetchVideoSummaries(uuidInput, 0, INITIAL_PAGINATION.size);
    } else {
      setVideoSummaries([]);
      setSelectedVideoIds([]);
      setVideoPagination(INITIAL_PAGINATION);
    }
  }, [uuidInput, currentUser, fetchVideoSummaries]);

  const deleteVideo = useCallback(async (id) => {
    if (!currentUser) return;
    setDeleting(true);
    try {
      await apiClient.preprocess.deleteVideoSummaryById({
        xUserId: currentUser.uuid,
        id
      });
      setSelectedVideoIds(prev => prev.filter(item => item !== id));
      await fetchVideoSummaries(uuidInput, videoPagination.page, videoPagination.size);
    } catch (err) {
      setError(err.message);
    } finally {
      setDeleting(false);
    }
  }, [currentUser, uuidInput, videoPagination, fetchVideoSummaries]);

  const clearVideoText = useCallback(async (id) => {
    if (!currentUser) return;
    setClearingText(true);
    try {
      await apiClient.preprocess.clearVideoSummaryTextById({
        xUserId: currentUser.uuid,
        id
      });
      await fetchVideoSummaries(uuidInput, videoPagination.page, videoPagination.size);
    } catch (err) {
      setError(err.message);
    } finally {
      setClearingText(false);
    }
  }, [currentUser, uuidInput, videoPagination, fetchVideoSummaries]);

  const batchDeleteVideos = useCallback(async (ids = selectedVideoIds) => {
    if (!currentUser || ids.length === 0) return;
    setBatchDeleting(true);
    try {
      await apiClient.preprocess.deleteVideoSummariesByIds({
        xUserId: currentUser.uuid,
        requestBody: ids
      });
      setSelectedVideoIds([]);
      await fetchVideoSummaries(uuidInput, 0, videoPagination.size);
    } catch (err) {
      setError(err.message);
    } finally {
      setBatchDeleting(false);
    }
  }, [currentUser, selectedVideoIds, uuidInput, videoPagination.size, fetchVideoSummaries]);

  const batchClearVideoTexts = useCallback(async (ids = selectedVideoIds) => {
    if (!currentUser || ids.length === 0) return;
    setBatchClearingText(true);
    try {
      await apiClient.preprocess.clearVideoSummaryTextsByIds({
        xUserId: currentUser.uuid,
        requestBody: ids
      });
      setSelectedVideoIds([]);
      await fetchVideoSummaries(uuidInput, 0, videoPagination.size);
    } catch (err) {
      setError(err.message);
    } finally {
      setBatchClearingText(false);
    }
  }, [currentUser, selectedVideoIds, uuidInput, videoPagination.size, fetchVideoSummaries]);

  return {
    videoSummaries,
    selectedVideoIds,
    setSelectedVideoIds,
    loadingVideos,
    videoPagination,
    deleting,
    clearingText,
    batchDeleting,
    batchClearingText,
    error,
    fetchVideoSummaries,
    deleteVideo,
    clearVideoText,
    batchDeleteVideos,
    batchClearVideoTexts
  };
}