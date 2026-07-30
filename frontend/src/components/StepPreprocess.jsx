import React, { useState, useEffect } from 'react';
import { styles } from '../styles/dashboardStyles';

export default function StepPreprocess({
  uuidInput,
  isPreprocessFinished,
  preprocessProgress,
  handleStartPreprocess,
  handleAbortPreprocess,
  loading,
  errorPreprocess,
  onNavigateToImages,
  onNavigateToAudios,
  apiUrl,
  currentUser,
  selectedStartTime,
  setSelectedStartTime,
  selectedEndTime,
  setSelectedEndTime
}) {
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

  // Fetch chat preview data once preprocessing is finished
  useEffect(() => {
    if (uuidInput && isCompleted && currentUser) {
      const fetchPreview = async () => {
        setLoadingPreview(true);
        try {
          const res = await fetch(`${apiUrl}/api/summary/preview/${uuidInput}`, {
            headers: { 'X-User-Id': currentUser.uuid }
          });
          if (res.ok) {
            const data = await res.json();
            setPreviewData(data);
          }
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
  }, [uuidInput, isCompleted, currentUser, apiUrl]);

  // Convert chat log timestamps like "2026-07-28 13:41:08" to "YYYY-MM-DDTHH:mm:ss" for datetime-local input
  const toLocalInputValue = (str) => {
    if (!str) return '';
    let cleaned = str.trim().replace('T', ' ');
    const match = cleaned.match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}):(\d{2}):(\d{2})/);
    if (match) {
      const [, date, hour, min, sec] = match;
      return `${date}T${hour}:${min}:${sec}`;
    }
    return '';
  };

  const fromLocalInputValue = (val) => {
    if (!val) return '';
    // val from datetime-local input is typically "YYYY-MM-DDTHH:mm"
    let formatted = val.replace('T', ' '); // Use a space instead of 'T'

    if (formatted.length === 16) {
      formatted += ':00'; // Appends seconds if missing -> "YYYY-MM-DD HH:mm:ss"
    } else if (formatted.length > 19) {
      formatted = formatted.substring(0, 19);
    }

    return formatted; // Returns e.g. "2026-07-28 18:33:27"
  };
  // Robust parsing to numeric epoch milliseconds for reliable comparison
  const parseToComparable = (str) => {
    if (!str) return 0;
    const formatted = str.includes('T') ? str : str.replace(' ', 'T');
    const timeValue = new Date(formatted).getTime();
    return isNaN(timeValue) ? 0 : timeValue;
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
    if (isCompleted) return 'Completed';
    if (isRunning) return 'Processing';
    if (isPaused) return 'Paused';
    return 'Pending';
  };

  return (
    <div style={styles.card}>
      <div style={styles.cardHeader}>
        <h3 style={styles.cardTitle}>Step 2: Clean & Preprocess</h3>
        <span style={{ ...styles.lockBadge, ...getBadgeStyle() }}>{getBadgeText()}</span>
      </div>

      {!uuidInput ? (
        <div style={styles.dbErrorBox}>Select or upload a dataset first.</div>
      ) : (
        <div style={styles.actionButtonGroup}>
          {/* Universal Access to Audio and Image Tables */}
          <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', marginBottom: '12px' }}>
            <button onClick={onNavigateToImages} style={styles.button}>📁 View Image Summaries</button>
            <button onClick={onNavigateToAudios} style={styles.button}>🎙️ View Audio Summaries</button>
          </div>

          {/* State: IDLING / Pending */}
          {!isCompleted && !isRunning && !isPaused && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              <button
                onClick={handleStartPreprocess}
                disabled={loading.preprocess}
                style={styles.buttonSuccess}
              >
                {loading.preprocess ? 'Starting...' : 'Start Preprocessing'}
              </button>
            </div>
          )}

          {/* State: PAUSED */}
          {isPaused && (
            <div style={styles.progressSection}>
              <div style={styles.progressLabelRow}>
                <span>Status: <strong>Paused</strong></span>
                <span>{progressVal}%</span>
              </div>
              <div style={styles.progressBarBg}>
                <div style={{ ...styles.progressBarFill, width: `${progressVal}%`, backgroundColor: '#d97706' }} />
              </div>
              {preprocessProgress.totalTasks != null && (
                <div style={{ fontSize: '0.8rem', color: '#6b7280', marginTop: '4px' }}>
                  Processed {preprocessProgress.completedTasks || 0} of {preprocessProgress.totalTasks} tasks
                  ({preprocessProgress.remainingTasks || 0} remaining)
                </div>
              )}
              <button
                onClick={handleStartPreprocess}
                disabled={loading.preprocess}
                style={{ ...styles.buttonSuccess, width: '100%', marginTop: '10px' }}
              >
                {loading.preprocess ? 'Resuming...' : 'Resume Preprocessing'}
              </button>
            </div>
          )}

          {/* State: RUNNING */}
          {isRunning && (
            <div style={styles.progressSection}>
              <div style={styles.progressLabelRow}>
                <span>Status: <strong>Running</strong></span>
                <span>{progressVal}%</span>
              </div>
              <div style={styles.progressBarBg}>
                <div style={{ ...styles.progressBarFill, width: `${progressVal}%`, backgroundColor: '#2563eb' }} />
              </div>
              {preprocessProgress.totalTasks != null && (
                <div style={{ fontSize: '0.8rem', color: '#6b7280', marginTop: '4px' }}>
                  Processed {preprocessProgress.completedTasks || 0} of {preprocessProgress.totalTasks} tasks
                  ({preprocessProgress.remainingTasks || 0} remaining)
                </div>
              )}
              <button
                onClick={handleAbortPreprocess}
                disabled={loading.abortPreprocess}
                style={{ ...styles.button, width: '100%', marginTop: '10px', backgroundColor: '#d97706', color: '#fff' }}
              >
                {loading.abortPreprocess ? 'Pausing...' : 'Pause'}
              </button>
            </div>
          )}

          {/* State: COMPLETED - Shows Success Text and Chat Log Preview Table with Calendar Time Window */}
          {isCompleted && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              <div style={{ fontSize: '0.85rem', color: '#059669', fontWeight: '600' }}>
                ✓ Preprocessing task finished successfully.
              </div>

              {/* Chat Log Preview & Calendar Time Window Selection */}
              <div style={{ border: '1px solid #e2e8f0', borderRadius: '8px', padding: '12px', background: '#f8fafc', marginTop: '8px' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
                  <h4 style={{ margin: 0, fontSize: '14px', color: '#334155' }}>
                    Chat Log Preview & Standard Timestamp Window
                  </h4>
                  <span style={{ fontSize: '11px', color: '#64748b' }}>
                    Click table rows to target exact timestamps
                  </span>
                </div>

                {previewData.metadata && Object.keys(previewData.metadata).length > 0 && (
                  <div style={{ fontSize: '12px', color: '#64748b', marginBottom: '8px' }}>
                    <strong>Group:</strong> {previewData.metadata['群名称'] || 'N/A'} |
                    <strong> Total Messages:</strong> {previewData.metadata['总消息数'] || previewData.rows.length}
                  </div>
                )}

                {/* Datetime Pickers Row */}
                <div style={{ display: 'flex', gap: '15px', alignItems: 'center', marginBottom: '10px', background: '#e2e8f0', padding: '8px 10px', borderRadius: '4px', flexWrap: 'wrap' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px' }}>
                    <label style={{ fontWeight: '500' }}>Start Time:</label>
                    <input
                      type="datetime-local"
                      value={toLocalInputValue(selectedStartTime)}
                      onChange={(e) => setSelectedStartTime(fromLocalInputValue(e.target.value))}
                      style={{ padding: '4px 6px', borderRadius: '4px', border: '1px solid #cbd5e1', fontSize: '12px', background: '#fff' }}
                    />
                  </div>

                  <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px' }}>
                    <label style={{ fontWeight: '500' }}>End Time:</label>
                    <input
                      type="datetime-local"
                      value={toLocalInputValue(selectedEndTime)}
                      onChange={(e) => setSelectedEndTime(fromLocalInputValue(e.target.value))}
                      style={{ padding: '4px 6px', borderRadius: '4px', border: '1px solid #cbd5e1', fontSize: '12px', background: '#fff' }}
                    />
                  </div>

                  {(selectedStartTime || selectedEndTime) && (
                    <button
                      type="button"
                      onClick={() => { setSelectedStartTime(''); setSelectedEndTime(''); }}
                      style={{ marginLeft: 'auto', background: 'transparent', border: 'none', color: '#dc2626', cursor: 'pointer', fontSize: '11px', fontWeight: 'bold' }}
                    >
                      Clear Selection
                    </button>
                  )}
                </div>

                {/* Table View */}
                <div style={{ maxHeight: '200px', overflowY: 'auto', border: '1px solid #e2e8f0', borderRadius: '4px', background: '#fff' }}>
                  {loadingPreview ? (
                    <div style={{ padding: '15px', textAlign: 'center', fontSize: '12px', color: '#64748b' }}>Loading preview table...</div>
                  ) : previewData.rows.length === 0 ? (
                    <div style={{ padding: '15px', textAlign: 'center', fontSize: '12px', color: '#64748b' }}>No preview rows available.</div>
                  ) : (
                    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12px', textAlign: 'left' }}>
                      <thead>
                        <tr style={{ background: '#f1f5f9', borderBottom: '1px solid #e2e8f0', position: 'sticky', top: 0, zIndex: 1 }}>
                          <th style={{ padding: '6px 8px', width: '50px' }}>Line</th>
                          <th style={{ padding: '6px 8px', width: '190px' }}>Timestamp</th>
                          <th style={{ padding: '6px 8px', width: '90px' }}>Sender</th>
                          <th style={{ padding: '6px 8px' }}>Content</th>
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
                                borderBottom: '1px solid #f1f5f9',
                                cursor: 'pointer',
                                backgroundColor: rowBg
                              }}
                            >
                              <td style={{ padding: '4px 8px', color: '#64748b' }}>{row.lineId}</td>
                              <td style={{ padding: '4px 8px', color: '#475569', fontFamily: 'monospace' }}>{row.timestamp}</td>
                              <td style={{ padding: '4px 8px', fontWeight: '500', color: '#334155' }}>{row.sender}</td>
                              <td style={{ padding: '4px 8px', color: '#1e293b', wordBreak: 'break-word' }}>{row.content}</td>
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