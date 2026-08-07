import React, { useState, useEffect } from 'react';
import { styles } from '../../styles/dashboardStyles';
import { apiClient } from '../../api/client';
import { toLocalInputValue, fromLocalInputValue, parseToComparable } from '../../utils/time';
import useLanguage from '../../hooks/useLanguage';

export default function StepPreprocess({
  uuidInput,
  isPreprocessFinished,
  preprocessProgress,
  handleStartPreprocess,
  handleAbortPreprocess,
  handleReprocessPreprocess,
  loading,
  errorPreprocess,
  onNavigateToImages,
  onNavigateToAudios,
  currentUser,
  selectedStartTime,
  setSelectedStartTime,
  selectedEndTime,
  setSelectedEndTime
}) {
  const { t } = useLanguage();
  const status = preprocessProgress ? preprocessProgress.status : 'IDLING';
  const isRunning = status === 'RUNNING';
  const isPaused = status === 'PAUSED';

  const isCompleted = status === 'COMPLETED' || (!preprocessProgress && isPreprocessFinished);

  const progressVal = preprocessProgress && preprocessProgress.progressPercentage != null
    ? Math.round(preprocessProgress.progressPercentage)
    : 0;

  // Chat Preview States specific to Step 2
  const [previewData, setPreviewData] = useState({ metadata: {}, rows: [] });
  const [loadingPreview, setLoadingPreview] = useState(false);
  const [previewVersion, setPreviewVersion] = useState(0);

  // Fetch chat preview data once preprocessing is finished
  useEffect(() => {
    if (uuidInput && isCompleted && currentUser) {
      const fetchPreview = async () => {
        setLoadingPreview(true);
        try {
          const data = await apiClient.chatSummary.getChatPreview({
            xUserId: currentUser.uuid,
            uuid: uuidInput
          });
          setPreviewData(data?.data || { metadata: {}, rows: [] });
        } catch (err) {
          console.error("Failed to load chat preview:", err);
        } finally {
          setLoadingPreview(false);
        }
      };
      fetchPreview();
    } else {
      setPreviewData({ metadata: {}, rows: [] });
    }
  }, [uuidInput, isCompleted, currentUser, previewVersion]);

  const handleReprocessClick = async () => {
    await handleReprocessPreprocess();
    setPreviewVersion(v => v + 1);
  };

  const handleRowClick = (row) => {
    const rowComp = parseToComparable(row.timestamp);
    const startComp = parseToComparable(selectedStartTime);
    const endComp = parseToComparable(selectedEndTime);
    const formattedRowTime = row.timestamp.includes('.') ? row.timestamp.substring(0, 19) : row.timestamp;

    // If no start time is set, or BOTH are already set, start a fresh range with just Start Time
    if (!selectedStartTime || (selectedStartTime && selectedEndTime)) {
      setSelectedStartTime(formattedRowTime);
      setSelectedEndTime('');
    }
    // If Start Time is set but End Time is empty
    else if (selectedStartTime && !selectedEndTime) {
      if (rowComp >= startComp) {
        setSelectedEndTime(formattedRowTime);
      } else {
        // If clicked row is earlier than start time, shift it to be the new start time
        setSelectedStartTime(formattedRowTime);
      }
    }
  };

  const getBadgeStyle = () => {
    if (isCompleted) return { backgroundColor: '#d1fae5', color: '#065f46', borderColor: '#A7F3D0' };
    if (isRunning) return { backgroundColor: '#dbeafe', color: '#1e40af', borderColor: '#BFDBFE' };
    if (isPaused) return { backgroundColor: '#fef3c7', color: '#92400e', borderColor: '#FDE68A' };
    return { backgroundColor: '#f3f4f6', color: '#4b5563', borderColor: '#E5E7EB' };
  };

  const getBadgeText = () => {
    if (isCompleted) return t('status.completed');
    if (isRunning) return t('status.processing');
    if (isPaused) return t('status.paused');
    return t('status.pending');
  };

  return (
    <div style={styles.card}>
      <div style={styles.cardHeader}>
        <h3 style={styles.cardTitle}>{t('preprocess.title')}</h3>
        <span style={{ ...styles.lockBadge, ...getBadgeStyle() }}>{getBadgeText()}</span>
      </div>

      {!uuidInput ? (
        <div style={styles.dbErrorBox}>{t('preprocess.selectDatasetFirst')}</div>
      ) : (
        <div style={styles.actionButtonGroup}>
          {/* Universal Access to Audio and Image Tables */}
          <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', marginBottom: '12px' }}>
            <button onClick={onNavigateToImages} style={styles.button}>{t('preprocess.viewImages')}</button>
            <button onClick={onNavigateToAudios} style={styles.button}>{t('preprocess.viewAudios')}</button>
          </div>

          {/* State: IDLING / Pending */}
          {!isCompleted && !isRunning && !isPaused && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              <button
                onClick={handleStartPreprocess}
                disabled={loading.preprocess}
                style={styles.buttonSuccess}
              >
                {loading.preprocess ? t('preprocess.starting') : t('preprocess.start')}
              </button>
            </div>
          )}

          {/* State: PAUSED */}
          {isPaused && (
            <div style={styles.progressSection}>
              <div style={styles.progressLabelRow}>
                <span>{t('common.status')} <strong>{t('status.paused')}</strong></span>
                <span>{progressVal}%</span>
              </div>
              <div style={styles.progressBarBg}>
                <div style={{ ...styles.progressBarFill, width: `${progressVal}%`, backgroundColor: '#d97706' }} />
              </div>
              {preprocessProgress.totalTasks != null && (
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: '4px' }}>
                  {t('preprocess.tasksProcessed', { done: preprocessProgress.completedTasks || 0, total: preprocessProgress.totalTasks, remaining: preprocessProgress.remainingTasks || 0 })}
                </div>
              )}
              <button
                onClick={handleStartPreprocess}
                disabled={loading.preprocess}
                style={{ ...styles.buttonSuccess, width: '100%', marginTop: '10px' }}
              >
                {loading.preprocess ? t('preprocess.resuming') : t('preprocess.resume')}
              </button>
            </div>
          )}

          {/* State: RUNNING */}
          {isRunning && (
            <div style={styles.progressSection}>
              <div style={styles.progressLabelRow}>
                <span>{t('common.status')} <strong>{t('status.running')}</strong></span>
                <span>{progressVal}%</span>
              </div>
              <div style={styles.progressBarBg}>
                <div style={{ ...styles.progressBarFill, width: `${progressVal}%`, backgroundColor: '#2563eb' }} />
              </div>
              {preprocessProgress.totalTasks != null && (
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: '4px' }}>
                  {t('preprocess.tasksProcessed', { done: preprocessProgress.completedTasks || 0, total: preprocessProgress.totalTasks, remaining: preprocessProgress.remainingTasks || 0 })}
                </div>
              )}
              <button
                onClick={handleAbortPreprocess}
                disabled={loading.abortPreprocess}
                style={{ ...styles.button, width: '100%', marginTop: '10px', backgroundColor: '#d97706', color: '#fff' }}
              >
                {loading.abortPreprocess ? t('preprocess.pausing') : t('preprocess.pause')}
              </button>
            </div>
          )}

          {/* State: COMPLETED - Shows Success Text and Chat Log Preview Table with Calendar Time Window */}
          {isCompleted && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              <div style={{ fontSize: '0.85rem', color: '#059669', fontWeight: '600' }}>
                {t('preprocess.finished')}
              </div>

              <button
                onClick={handleReprocessClick}
                disabled={loading.reprocess}
                style={{ ...styles.button, width: '100%' }}
              >
                {loading.reprocess ? t('preprocess.reprocessing') : t('preprocess.reprocess')}
              </button>

              {/* Chat Log Preview & Calendar Time Window Selection */}
              <div style={{ border: '1px solid var(--border)', borderRadius: '8px', padding: '12px', background: 'var(--bg-card)', marginTop: '8px' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
                  <h4 style={{ margin: 0, fontSize: '14px', color: 'var(--text-primary)' }}>
                    {t('preprocess.previewTitle')}
                  </h4>
                  <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
                    {t('preprocess.previewHint')}
                  </span>
                </div>

                {previewData.metadata && Object.keys(previewData.metadata).length > 0 && (
                  <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '8px' }}>
                    <strong>{t('preprocess.group')}</strong> {previewData.metadata['群名称'] || 'N/A'} |
                    <strong> {t('preprocess.totalMessages')}</strong> {previewData.metadata['总消息数'] || previewData.rows.length}
                  </div>
                )}

                {/* Datetime Pickers Row */}
                <div style={{ display: 'flex', gap: '15px', alignItems: 'center', marginBottom: '10px', background: 'var(--bg-subtle)', padding: '8px 10px', borderRadius: '4px', flexWrap: 'wrap' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px' }}>
                    <label style={{ fontWeight: '500' }}>{t('preprocess.startTime')}</label>
                    <input
                      type="datetime-local"
                      value={toLocalInputValue(selectedStartTime)}
                      onChange={(e) => setSelectedStartTime(fromLocalInputValue(e.target.value))}
                      style={{ padding: '4px 6px', borderRadius: '4px', border: '1px solid var(--border-strong)', fontSize: '12px', background: 'var(--bg-card)', color: 'var(--text-primary)' }}
                    />
                  </div>

                  <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px' }}>
                    <label style={{ fontWeight: '500' }}>{t('preprocess.endTime')}</label>
                    <input
                      type="datetime-local"
                      value={toLocalInputValue(selectedEndTime)}
                      onChange={(e) => setSelectedEndTime(fromLocalInputValue(e.target.value))}
                      style={{ padding: '4px 6px', borderRadius: '4px', border: '1px solid var(--border-strong)', fontSize: '12px', background: 'var(--bg-card)', color: 'var(--text-primary)' }}
                    />
                  </div>

                  {(selectedStartTime || selectedEndTime) && (
                    <button
                      type="button"
                      onClick={() => { setSelectedStartTime(''); setSelectedEndTime(''); }}
                      style={{ marginLeft: 'auto', background: 'transparent', border: 'none', color: '#dc2626', cursor: 'pointer', fontSize: '11px', fontWeight: 'bold' }}
                    >
                      {t('preprocess.clearSelection')}
                    </button>
                  )}
                </div>

                {/* Table View */}
                <div style={{ maxHeight: '200px', overflowY: 'auto', border: '1px solid var(--border)', borderRadius: '4px', background: 'var(--bg-card)' }}>
                  {loadingPreview ? (
                    <div style={{ padding: '15px', textAlign: 'center', fontSize: '12px', color: 'var(--text-muted)' }}>{t('preprocess.loadingPreview')}</div>
                  ) : previewData.rows.length === 0 ? (
                    <div style={{ padding: '15px', textAlign: 'center', fontSize: '12px', color: 'var(--text-muted)' }}>{t('preprocess.noPreview')}</div>
                  ) : (
                    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12px', textAlign: 'left' }}>
                      <thead>
                        <tr style={{ background: 'var(--bg-subtle)', borderBottom: '1px solid var(--border)', position: 'sticky', top: 0, zIndex: 1 }}>
                          <th style={{ padding: '6px 8px', width: '50px' }}>{t('table.line')}</th>
                          <th style={{ padding: '6px 8px', width: '190px' }}>{t('table.timestamp')}</th>
                          <th style={{ padding: '6px 8px', width: '90px' }}>{t('table.sender')}</th>
                          <th style={{ padding: '6px 8px' }}>{t('table.content')}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {previewData.rows.map((row) => {
                          const rowComp = parseToComparable(row.timestamp);
                          const startComp = parseToComparable(selectedStartTime);
                          const endComp = parseToComparable(selectedEndTime);

                          const isSelectedStart = Boolean(startComp && rowComp === startComp);
                          const isSelectedEnd = Boolean(endComp && rowComp === endComp);
                          const isInRange = Boolean(startComp && endComp && rowComp > startComp && rowComp < endComp);

                          let rowBg = 'transparent';
                          if (isSelectedStart || isSelectedEnd) {
                            rowBg = '#bae6fd';
                          } else if (isInRange) {
                            rowBg = '#e0f2fe';
                          }

                          return (
                            <tr
                              key={row.lineId}
                              onClick={() => handleRowClick(row)}
                              style={{
                                borderBottom: '1px solid var(--border)',
                                cursor: 'pointer',
                                backgroundColor: rowBg
                              }}
                            >
                              <td style={{ padding: '4px 8px', color: 'var(--text-muted)' }}>{row.lineId}</td>
                              <td style={{ padding: '4px 8px', color: 'var(--text-secondary)', fontFamily: 'monospace' }}>{row.timestamp}</td>
                              <td style={{ padding: '4px 8px', fontWeight: '500', color: 'var(--text-secondary)' }}>{row.sender}</td>
                              <td style={{ padding: '4px 8px', color: 'var(--text-primary)', wordBreak: 'break-word' }}>{row.content}</td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  )}
                </div>
              </div>
            </div>
          )}
        </div>
      )}

      {errorPreprocess && <div style={styles.errorText}>⚠️ {errorPreprocess}</div>}
    </div>
  );
}