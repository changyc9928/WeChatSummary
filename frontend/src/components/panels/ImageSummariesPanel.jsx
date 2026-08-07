import React, { useState, useEffect } from 'react';
import { styles } from '../../styles/dashboardStyles';
import { apiClient } from '../../api/client';

export default function ImageSummariesPanel({
  uuidInput,
  imageSummaries,
  loadingImages,
  imagePagination,
  fetchImageSummaries,
  selectedImageIds,
  setSelectedImageIds,
  handleDeleteImage,
  handleBatchDeleteImages,
  setActiveModalImage,
  loading,
  errorImages,
  currentUser
}) {
  const [imageObjectUrls, setImageObjectUrls] = useState({});

  // Fetch image bytes with X-User-Id header and create local Object URLs
  useEffect(() => {
    const objectUrls = {};
    let isMounted = true;

    const loadImages = async () => {
      if (!imageSummaries || imageSummaries.length === 0 || !currentUser) return;

      for (const item of imageSummaries) {
        try {
          const blob = await apiClient.preprocess.getImageFileById({
            xUserId: currentUser.uuid,
            id: item.id
          });
          if (isMounted) {
            objectUrls[item.id] = URL.createObjectURL(blob);
            setImageObjectUrls({ ...objectUrls });
          }
        } catch (err) {
          console.error(`Failed to load image for id ${item.id}`, err);
        }
      }
    };

    loadImages();

    return () => {
      isMounted = false;
      // Cleanup object URLs to prevent memory leaks
      Object.values(objectUrls).forEach(url => URL.revokeObjectURL(url));
    };
  }, [imageSummaries, currentUser]);

  const toggleSelectAll = (e) => {
    if (e.target.checked) {
      setSelectedImageIds(imageSummaries.map(item => item.id));
    } else {
      setSelectedImageIds([]);
    }
  };

  const toggleSelectOne = (id) => {
    setSelectedImageIds(prev => prev.includes(id) ? prev.filter(i => i !== id) : [...prev, id]);
  };

  if (!uuidInput) return null;

  return (
    <div style={{ ...styles.card, gridColumn: '1 / -1' }}>
      <div style={styles.cardHeader}>
        <h3 style={styles.cardTitle}>Image Summaries & Records</h3>
        {selectedImageIds.length > 0 && (
          <button onClick={handleBatchDeleteImages} disabled={loading.batchDeleteImages} style={styles.buttonDangerSmall}>
            {loading.batchDeleteImages ? 'Deleting...' : `Delete Selected (${selectedImageIds.length})`}
          </button>
        )}
      </div>

      {errorImages && <div style={styles.errorText}>⚠️ {errorImages}</div>}

      {loadingImages ? (
        <div style={{ textAlign: 'center', padding: '20px', color: 'var(--text-muted)' }}>Loading image summaries...</div>
      ) : imageSummaries.length === 0 ? (
        <div style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>No processed images found for this dataset.</div>
      ) : (
        <>
          <div style={styles.tableWrapper}>
            <table style={styles.table}>
              <thead>
                <tr style={styles.tr}>
                  <th style={styles.th}><input type="checkbox" onChange={toggleSelectAll} checked={selectedImageIds.length === imageSummaries.length && imageSummaries.length > 0} /></th>
                  <th style={styles.th}>Thumbnail</th>
                  <th style={styles.th}>AI Description / Summary</th>
                  <th style={styles.th}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {imageSummaries.map((item) => {
                  const objectUrl = imageObjectUrls[item.id];

                  return (
                    <tr key={item.id} style={styles.tr}>
                      <td style={styles.td}><input type="checkbox" checked={selectedImageIds.includes(item.id)} onChange={() => toggleSelectOne(item.id)} /></td>
                      <td style={styles.td}>
                        <div style={styles.thumbnailContainer} onClick={() => objectUrl && setActiveModalImage({ url: objectUrl, summary: item.summary })}>
                          {objectUrl ? (
                            <img src={objectUrl} alt="thumbnail" style={styles.thumbnail} />
                          ) : (
                            <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textAlign: 'center' }}>Loading...</div>
                          )}
                          <div style={styles.thumbnailOverlay}>View</div>
                        </div>
                      </td>
                      <td style={styles.td}>
                        <div style={styles.summaryText}>{item.summary || <span style={styles.emptySummaryBadge}>No summary generated</span>}</div>
                      </td>
                      <td style={styles.td}>
                        <button onClick={() => handleDeleteImage(item.id)} disabled={loading.deleteImage} style={styles.buttonDangerSmall}>Delete</button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          <div style={styles.paginationContainer}>
            <span style={styles.paginationInfo}>Page {imagePagination.page + 1} of {imagePagination.totalPages || 1} ({imagePagination.totalElements} total items)</span>
            <div style={styles.paginationControls}>
              <button disabled={imagePagination.isFirst} onClick={() => fetchImageSummaries(uuidInput, imagePagination.page - 1, imagePagination.size)} style={imagePagination.isFirst ? styles.pageButtonDisabled : styles.pageButton}>Previous</button>
              <button disabled={imagePagination.isLast} onClick={() => fetchImageSummaries(uuidInput, imagePagination.page + 1, imagePagination.size)} style={imagePagination.isLast ? styles.pageButtonDisabled : styles.pageButton}>Next</button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}