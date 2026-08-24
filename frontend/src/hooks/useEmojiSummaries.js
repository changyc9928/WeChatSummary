import { useCallback, useEffect, useState } from 'react';
import { apiClient } from '../api/client';

export const INITIAL_PAGINATION = {
  page: 0,
  size: 20,
  totalPages: 0,
  totalElements: 0,
  isFirst: true,
  isLast: true
};

export default function useEmojiSummaries({ uuidInput, currentUser }) {
  const [emojiSummaries, setEmojiSummaries] = useState([]);
  const [selectedEmojiIds, setSelectedEmojiIds] = useState([]);
  const [loadingEmojis, setLoadingEmojis] = useState(false);
  const [emojiPagination, setEmojiPagination] = useState(INITIAL_PAGINATION);
  const [deleting, setDeleting] = useState(false);
  const [batchDeleting, setBatchDeleting] = useState(false);
  const [error, setError] = useState(null);

  const fetchEmojiSummaries = useCallback(async (sessionUuid, page = 0, size = 20) => {
    if (!sessionUuid || !currentUser) {
      setEmojiSummaries([]);
      return;
    }
    setLoadingEmojis(true);
    try {
      const data = await apiClient.preprocess.getEmojiSummariesByUuid({
        xUserId: currentUser.uuid,
        uuid: sessionUuid,
        page,
        size
      });
      const pageData = data?.data;
      setEmojiSummaries(pageData?.content || []);
      setEmojiPagination({
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
      setLoadingEmojis(false);
    }
  }, [currentUser]);

  useEffect(() => {
    if (uuidInput && currentUser) {
      fetchEmojiSummaries(uuidInput, 0, INITIAL_PAGINATION.size);
    } else {
      setEmojiSummaries([]);
      setSelectedEmojiIds([]);
      setEmojiPagination(INITIAL_PAGINATION);
    }
  }, [uuidInput, currentUser, fetchEmojiSummaries]);

  const deleteEmoji = useCallback(async (id) => {
    if (!currentUser) return;
    setDeleting(true);
    try {
      await apiClient.preprocess.deleteEmojiSummaryById({
        xUserId: currentUser.uuid,
        id
      });
      setSelectedEmojiIds(prev => prev.filter(item => item !== id));
      await fetchEmojiSummaries(uuidInput, emojiPagination.page, emojiPagination.size);
    } catch (err) {
      setError(err.message);
    } finally {
      setDeleting(false);
    }
  }, [currentUser, uuidInput, emojiPagination, fetchEmojiSummaries]);

  const batchDeleteEmojis = useCallback(async (ids = selectedEmojiIds) => {
    if (!currentUser || ids.length === 0) return;
    setBatchDeleting(true);
    try {
      await apiClient.preprocess.deleteEmojiSummariesByIds({
        xUserId: currentUser.uuid,
        requestBody: ids
      });
      setSelectedEmojiIds([]);
      await fetchEmojiSummaries(uuidInput, 0, emojiPagination.size);
    } catch (err) {
      setError(err.message);
    } finally {
      setBatchDeleting(false);
    }
  }, [currentUser, selectedEmojiIds, uuidInput, emojiPagination.size, fetchEmojiSummaries]);

  return {
    emojiSummaries,
    selectedEmojiIds,
    setSelectedEmojiIds,
    loadingEmojis,
    emojiPagination,
    deleting,
    batchDeleting,
    error,
    fetchEmojiSummaries,
    deleteEmoji,
    batchDeleteEmojis
  };
}
