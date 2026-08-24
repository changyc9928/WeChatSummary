import React, { useState, useEffect } from 'react';
import { styles } from '../../styles/dashboardStyles';
import { apiClient } from '../../api/client';
import useLanguage from '../../hooks/useLanguage';

function VideoPlayer({ id, currentUser }) {
  const [url, setUrl] = useState(null);
  const [loading, setLoading] = useState(true);
  const [enlarged, setEnlarged] = useState(false);

  useEffect(() => {
    if (!id || !currentUser) return;
    let objectUrl;
    let active = true;
    setLoading(true);
    apiClient.preprocess.getVideoFileById({ xUserId: currentUser.uuid, id })
      .then((blob) => {
        if (!active) return;
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch(() => {})
      .finally(() => { if (active) setLoading(false); });
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [id, currentUser]);

  if (loading) return <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>…</span>;
  if (!url) return <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>—</span>;

  if (enlarged) {
    return (
      <div
        onClick={() => setEnlarged(false)}
        style={{
          position: 'fixed', inset: 0, zIndex: 1000,
          background: 'rgba(0,0,0,0.85)',
          display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: '12px'
        }}
      >
        <video
          controls
          src={url}
          onClick={(e) => e.stopPropagation()}
          style={{ width: '90vw', maxWidth: '1100px', maxHeight: '82vh', borderRadius: '8px', background: '#000' }}
        />
        <button
          onClick={(e) => { e.stopPropagation(); setEnlarged(false); }}
          style={{ padding: '8px 18px', borderRadius: '6px', border: 'none', cursor: 'pointer', background: 'rgba(255,255,255,0.15)', color: '#fff', fontSize: '0.9rem' }}
        >关闭 / Close</button>
      </div>
    );
  }

  return (
    <div style={{ position: 'relative', display: 'inline-block' }}>
      <video
        controls
        muted
        src={url}
        style={{ width: '100%', borderRadius: '6px', background: '#000', display: 'block' }}
      />
      <button
        onClick={() => setEnlarged(true)}
        title="放大 / Enlarge (then use the fullscreen icon)"
        style={{
          position: 'absolute', top: '6px', right: '6px',
          width: '30px', height: '30px', borderRadius: '4px', border: 'none', cursor: 'pointer',
          background: 'rgba(0,0,0,0.55)', color: '#fff', fontSize: '15px', lineHeight: '30px', padding: 0
        }}
      >⛶</button>
    </div>
  );
}

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
  errorVideos,
  currentUser
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
            <table style={{ ...styles.table, tableLayout: 'fixed' }}>
              <thead>
                <tr style={styles.tr}>
                  <th style={{ ...styles.th, width: '40px' }}><input type="checkbox" onChange={toggleSelectAll} checked={selectedVideoIds.length === safeSummaries.length && safeSummaries.length > 0} /></th>
                  <th style={styles.th}>{t('videos.playback')}</th>
                  <th style={styles.th}>{t('videos.transcript')}</th>
                  <th style={styles.th}>{t('videos.aiSummary')}</th>
                  <th style={{ ...styles.th, width: '110px' }}>{t('common.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {safeSummaries.map((item) => (
                  <tr key={item.id} style={styles.tr}>
                    <td style={styles.td}><input type="checkbox" checked={selectedVideoIds.includes(item.id)} onChange={() => toggleSelectOne(item.id)} /></td>
                    <td style={styles.td}>
                      <VideoPlayer id={item.id} currentUser={currentUser} />
                    </td>
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