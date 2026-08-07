import { useCallback, useEffect, useState } from 'react';
import * as preprocessApi from '../api/preprocessApi';

export const INITIAL_PAGINATION = {
  page: 0,
  size: 20,
  totalPages: 0,
  totalElements: 0,
  isFirst: true,
  isLast: true
};

export default function useImageSummaries({ uuidInput, currentUser }) {
  const [imageSummaries, setImageSummaries] = useState([]);
  const [selectedImageIds, setSelectedImageIds] = useState([]);
  const [loadingImages, setLoadingImages] = useState(false);
  const [imagePagination, setImagePagination] = useState(INITIAL_PAGINATION);
  const [deleting, setDeleting] = useState(false);
  const [batchDeleting, setBatchDeleting] = useState(false);
  const [error, setError] = useState(null);

  const fetchImageSummaries = useCallback(async (sessionUuid, page = 0, size = 20) => {
    if (!sessionUuid || !currentUser) {
      setImageSummaries([]);
      return;
    }
    setLoadingImages(true);
    try {
      const data = await preprocessApi.getImageSummaries(sessionUuid, page, size, currentUser.uuid);
      setImageSummaries(data.content || []);
      setImagePagination({
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
      setLoadingImages(false);
    }
  }, [currentUser]);

  useEffect(() => {
    if (uuidInput && currentUser) {
      fetchImageSummaries(uuidInput, 0, INITIAL_PAGINATION.size);
    } else {
      setImageSummaries([]);
      setSelectedImageIds([]);
      setImagePagination(INITIAL_PAGINATION);
    }
  }, [uuidInput, currentUser, fetchImageSummaries]);

  const deleteImage = useCallback(async (id) => {
    if (!currentUser) return;
    setDeleting(true);
    try {
      await preprocessApi.deleteImageSummary(id, currentUser.uuid);
      setSelectedImageIds(prev => prev.filter(item => item !== id));
      await fetchImageSummaries(uuidInput, imagePagination.page, imagePagination.size);
    } catch (err) {
      setError(err.message);
    } finally {
      setDeleting(false);
    }
  }, [currentUser, uuidInput, imagePagination, fetchImageSummaries]);

  const batchDeleteImages = useCallback(async (ids = selectedImageIds) => {
    if (!currentUser || ids.length === 0) return;
    setBatchDeleting(true);
    try {
      await preprocessApi.batchDeleteImageSummaries(ids, currentUser.uuid);
      setSelectedImageIds([]);
      await fetchImageSummaries(uuidInput, 0, imagePagination.size);
    } catch (err) {
      setError(err.message);
    } finally {
      setBatchDeleting(false);
    }
  }, [currentUser, selectedImageIds, uuidInput, imagePagination.size, fetchImageSummaries]);

  return {
    imageSummaries,
    selectedImageIds,
    setSelectedImageIds,
    loadingImages,
    imagePagination,
    deleting,
    batchDeleting,
    error,
    fetchImageSummaries,
    deleteImage,
    batchDeleteImages
  };
}