import React from 'react';
import { styles } from '../../styles/dashboardStyles';
import useLanguage from '../../hooks/useLanguage';

export default function VideoSummariesPanel({
  uuidInput,
  videoSummaries = [],
  loadingVideos,
  videoPagination,
  fetchVideoSummaries,
  selectedVideoIds = [],
  setSelectedVideoIds,
  handleDeleteVideo,
  handleClearVideoText,
  handleBatchDeleteVideos,
  handleBatchClearVideoTexts,
  loading,
  errorVideos
}) {
  const { t } = useLanguage();
  const toggleSelectAll = (e) => {
    if (!Array.isArray(videoSummaries)) return;
    if (e.target.checked) {
      setSelectedVideoIds(videoSummaries.map(item => item.id));
    } else {
      setSelectedVideoIds([]);
    }
  };

  const toggleSelectOne = (id) => {
    setSelectedVideoIds(prev => prev.includes(id) ? prev.filter(i => i !== id) : [...prev, id]);
  };

  // Safe fallback instead of returning null blindly which causes a blank page
  if (!uuidInput) {
    return (
      <div style={{ ...styles.card, gridColumn: '1 / -1', textAlign: 'center', padding: '30px' }}>
        <h3 style={styles.cardTitle}>{t('videos.panelTitle')}</h3>
        <p style={{ color: 'var(--text-muted)', marginTop: '10px' }}>{t('videos.noDataset')}</p>
      </div>
    );
  }

  const safeSummaries = Array.isArray(videoSummaries) ? videoSummaries : [];

  return (
    <div style={{ ...styles.card, gridColumn: '1 / -1' }}>
      <div style={styles.cardHeader}>
        <h3 style={styles.cardTitle}>{t('videos.panelTitle')}</h3>
        {selectedVideoIds.length > 0 && (
          <div style={{ display: 'flex', gap: '8px' }}>
            <button onClick={handleBatchClearVideoTexts} disabled={loading.batchClearVideoTexts} style={styles.buttonWarningSmall}>{t('videos.clearTextCount', { count: selectedVideoIds.length })}</button>
            <button onClick={handleBatchDeleteVideos} disabled={loading.batchDeleteVideos} style={styles.buttonDangerSmall}>{t('videos.deleteCount', { count: selectedVideoIds.length })}</button>
          </div>
        )}
      </div>

      {errorVideos && <div style={styles.errorText}>⚠️ {errorVideos}</div>}

      {loadingVideos ? (
        <div style={{ textAlign: 'center', padding: '20px', color: 'var(--text-muted)' }}>{t('videos.loading')}</div>
      ) : safeSummaries.length === 0 ? (
        <div style={{ color: 'var(--text-muted)', fontSize: '0.85rem', padding: '15px 0' }}>{t('videos.none')}</div>
      ) : (
        <>
          <div style={styles.tableWrapper}>
            <table style={styles.table}>
              <thead>
                <tr style={styles.tr}>
                  <th style={styles.th}><input type="checkbox" onChange={toggleSelectAll} checked={selectedVideoIds.length === safeSummaries.length && safeSummaries.length > 0} /></th>
                  <th style={styles.th}>{t('videos.transcript')}</th>
                  <th style={styles.th}>{t('videos.aiSummary')}</th>
                  <th style={styles.th}>{t('common.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {safeSummaries.map((item) => (
                  <tr key={item.id} style={styles.tr}>
                    <td style={styles.td}><input type="checkbox" checked={selectedVideoIds.includes(item.id)} onChange={() => toggleSelectOne(item.id)} /></td>
                    <td style={styles.td}>
                      <div style={styles.transcriptBox}>{item.transcript || <span style={styles.emptySummaryBadge}>{t('videos.noTranscript')}</span>}</div>
                    </td>
                    <td style={styles.td}>
                      <div style={styles.summaryText}>{item.summary || <span style={styles.emptySummaryBadge}>{t('common.noSummary')}</span>}</div>
                    </td>
                    <td style={styles.td}>
                      <div style={{ display: 'flex', gap: '6px', flexDirection: 'column' }}>
                        <button onClick={() => handleClearVideoText(item.id)} disabled={loading.clearVideoText} style={styles.buttonWarningSmall}>{t('videos.clearText')}</button>
                        <button onClick={() => handleDeleteVideo(item.id)} disabled={loading.deleteVideo} style={styles.buttonDangerSmall}>{t('common.delete')}</button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div style={styles.paginationContainer}>
            <span style={styles.paginationInfo}>{t('pagination.pageInfo', { page: (videoPagination?.page || 0) + 1, totalPages: videoPagination?.totalPages || 1, total: videoPagination?.totalElements || 0 })}</span>
            <div style={styles.paginationControls}>
              <button disabled={videoPagination?.isFirst} onClick={() => fetchVideoSummaries(uuidInput, videoPagination.page - 1, videoPagination.size)} style={videoPagination?.isFirst ? styles.pageButtonDisabled : styles.pageButton}>{t('pagination.previous')}</button>
              <button disabled={videoPagination?.isLast} onClick={() => fetchVideoSummaries(uuidInput, videoPagination.page + 1, videoPagination.size)} style={videoPagination?.isLast ? styles.pageButtonDisabled : styles.pageButton}>{t('pagination.next')}</button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}