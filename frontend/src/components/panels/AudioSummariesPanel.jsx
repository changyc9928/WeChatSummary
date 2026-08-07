import React from 'react';
import { styles } from '../../styles/dashboardStyles';

export default function AudioSummariesPanel({
  uuidInput,
  audioSummaries = [],
  loadingAudios,
  audioPagination,
  fetchAudioSummaries,
  selectedAudioIds = [],
  setSelectedAudioIds,
  handleDeleteAudio,
  handleClearAudioText,
  handleBatchDeleteAudios,
  handleBatchClearAudioTexts,
  loading,
  errorAudios
}) {
  const toggleSelectAll = (e) => {
    if (!Array.isArray(audioSummaries)) return;
    if (e.target.checked) {
      setSelectedAudioIds(audioSummaries.map(item => item.id));
    } else {
      setSelectedAudioIds([]);
    }
  };

  const toggleSelectOne = (id) => {
    setSelectedAudioIds(prev => prev.includes(id) ? prev.filter(i => i !== id) : [...prev, id]);
  };

  // Safe fallback instead of returning null blindly which causes a blank page
  if (!uuidInput) {
    return (
      <div style={{ ...styles.card, gridColumn: '1 / -1', textAlign: 'center', padding: '30px' }}>
        <h3 style={styles.cardTitle}>Audio Transcripts & Summaries</h3>
        <p style={{ color: 'var(--text-muted)', marginTop: '10px' }}>No active dataset selected. Please choose or upload a dataset from the dashboard.</p>
      </div>
    );
  }

  const safeSummaries = Array.isArray(audioSummaries) ? audioSummaries : [];

  return (
    <div style={{ ...styles.card, gridColumn: '1 / -1' }}>
      <div style={styles.cardHeader}>
        <h3 style={styles.cardTitle}>Audio Transcripts & Summaries</h3>
        {selectedAudioIds.length > 0 && (
          <div style={{ display: 'flex', gap: '8px' }}>
            <button onClick={handleBatchClearAudioTexts} disabled={loading.batchClearAudioTexts} style={styles.buttonWarningSmall}>Clear Text ({selectedAudioIds.length})</button>
            <button onClick={handleBatchDeleteAudios} disabled={loading.batchDeleteAudios} style={styles.buttonDangerSmall}>Delete ({selectedAudioIds.length})</button>
          </div>
        )}
      </div>

      {errorAudios && <div style={styles.errorText}>⚠️ {errorAudios}</div>}

      {loadingAudios ? (
        <div style={{ textAlign: 'center', padding: '20px', color: 'var(--text-muted)' }}>Loading audio elements...</div>
      ) : safeSummaries.length === 0 ? (
        <div style={{ color: 'var(--text-muted)', fontSize: '0.85rem', padding: '15px 0' }}>No processed audio logs found for this dataset.</div>
      ) : (
        <>
          <div style={styles.tableWrapper}>
            <table style={styles.table}>
              <thead>
                <tr style={styles.tr}>
                  <th style={styles.th}><input type="checkbox" onChange={toggleSelectAll} checked={selectedAudioIds.length === safeSummaries.length && safeSummaries.length > 0} /></th>
                  <th style={styles.th}>Transcript Text</th>
                  <th style={styles.th}>AI Audio Summary</th>
                  <th style={styles.th}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {safeSummaries.map((item) => (
                  <tr key={item.id} style={styles.tr}>
                    <td style={styles.td}><input type="checkbox" checked={selectedAudioIds.includes(item.id)} onChange={() => toggleSelectOne(item.id)} /></td>
                    <td style={styles.td}>
                      <div style={styles.transcriptBox}>{item.transcript || <span style={styles.emptySummaryBadge}>No transcript text</span>}</div>
                    </td>
                    <td style={styles.td}>
                      <div style={styles.summaryText}>{item.summary || <span style={styles.emptySummaryBadge}>No summary generated</span>}</div>
                    </td>
                    <td style={styles.td}>
                      <div style={{ display: 'flex', gap: '6px', flexDirection: 'column' }}>
                        <button onClick={() => handleClearAudioText(item.id)} disabled={loading.clearAudioText} style={styles.buttonWarningSmall}>Clear Text</button>
                        <button onClick={() => handleDeleteAudio(item.id)} disabled={loading.deleteAudio} style={styles.buttonDangerSmall}>Delete</button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div style={styles.paginationContainer}>
            <span style={styles.paginationInfo}>Page {(audioPagination?.page || 0) + 1} of {audioPagination?.totalPages || 1} ({audioPagination?.totalElements || 0} total items)</span>
            <div style={styles.paginationControls}>
              <button disabled={audioPagination?.isFirst} onClick={() => fetchAudioSummaries(uuidInput, audioPagination.page - 1, audioPagination.size)} style={audioPagination?.isFirst ? styles.pageButtonDisabled : styles.pageButton}>Previous</button>
              <button disabled={audioPagination?.isLast} onClick={() => fetchAudioSummaries(uuidInput, audioPagination.page + 1, audioPagination.size)} style={audioPagination?.isLast ? styles.pageButtonDisabled : styles.pageButton}>Next</button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}