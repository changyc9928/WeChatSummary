import React from 'react';
import { styles } from '../styles/dashboardStyles';

export default function StepSummary({
  uuidInput,
  isPreprocessFinished,
  summaryState = {},
  handleStartSummary,
  handlePauseSummary,
  handleRestartSummary,
  loading = {},
  selectedStartTime,
  selectedEndTime
}) {
  const currentStatus = (summaryState?.status || 'INITIAL_STATE').toUpperCase();

  const isRunning = currentStatus === 'RUNNING';
  const isPaused = currentStatus === 'PAUSED';
  const isIdling = currentStatus === 'IDLING';
  const isFinished = currentStatus === 'SUCCESS' || currentStatus === 'COMPLETED';

  const rawProg = summaryState?.progress || 0;
  const progressFraction = rawProg > 1 ? rawProg / 100 : rawProg;
  const progressPercent = Math.round(progressFraction * 100);

  const formatFromDatetimeLocal = (localStr) => {
    if (!localStr) return '';

    // Convert 'T' to space to match backend's expected 'YYYY-MM-DD HH:mm:ss' format
    let formatted = localStr.replace('T', ' ');

    if (formatted.length === 16) {
      formatted += ':00'; // Appends seconds if missing -> "YYYY-MM-DD HH:mm:ss"
    } else if (formatted.length > 19) {
      formatted = formatted.substring(0, 19);
    }

    return formatted; // Produces clean "2026-07-28 18:29:44"
  };

  const handleStartWithParams = (isRestart = false) => {
    const payload = {};
    if (selectedStartTime) {
      payload.startTime = formatFromDatetimeLocal(selectedStartTime);
    }
    if (selectedEndTime) {
      payload.endTime = formatFromDatetimeLocal(selectedEndTime);
    }

    if (isRestart) {
      handleRestartSummary(payload);
    } else {
      handleStartSummary(payload);
    }
  };

  const rawResult = summaryState?.result;
  const displayResultText = typeof rawResult === 'string'
    ? rawResult
    : (rawResult ? JSON.stringify(rawResult, null, 2) : '');

  return (
    <div style={styles.card}>
      <div style={styles.cardHeader}>
        <h3 style={styles.cardTitle}>Step 3: AI Summary</h3>
        <span style={styles.lockBadge}>{currentStatus}</span>
      </div>

      {!uuidInput ? (
        <div style={styles.dbErrorBox}>Select a dataset first.</div>
      ) : !isPreprocessFinished ? (
        <div style={styles.dbErrorBox}>Complete Step 2 preprocessing first.</div>
      ) : (
        <div style={styles.actionButtonGroup}>

          {(currentStatus === 'INITIAL_STATE' || isIdling) && (
            <button type="button" onClick={() => handleStartWithParams(false)} disabled={loading.start} style={styles.button}>
              {loading.start ? 'Starting Engine...' : 'Run Summary Engine'}
            </button>
          )}

          {isRunning && (
            <div style={styles.progressSection}>
              <div style={styles.progressLabelRow}>
                <span>Generating summary items...</span>
                <span>{progressPercent}%</span>
              </div>
              <div style={styles.progressBarBg}>
                <div style={{ ...styles.progressBarFill, width: `${progressPercent}%`, backgroundColor: '#d97706' }} />
              </div>
              <button type="button" onClick={handlePauseSummary} disabled={loading.pauseSummary} style={styles.buttonWarningSmall}>
                {loading.pauseSummary ? 'Pausing...' : 'Pause Summary'}
              </button>
            </div>
          )}

          {isPaused && (
            <div style={styles.actionButtonRow}>
              <button type="button" onClick={() => handleStartWithParams(false)} disabled={loading.start} style={styles.button}>Resume</button>
              <button type="button" onClick={() => handleStartWithParams(true)} disabled={loading.restartSummary} style={styles.buttonWarningSmall}>Restart</button>
            </div>
          )}

          {(isFinished || displayResultText) && (
            <div style={styles.summaryContainer}>
              <div style={styles.summaryLabel}>Final Generated Summary:</div>
              <div style={{
                ...styles.cleanSummaryOutput,
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
                maxHeight: '400px',
                overflowY: 'auto',
                padding: '12px',
                background: '#f8fafc',
                borderRadius: '6px',
                border: '1px solid #e2e8f0',
                fontSize: '14px',
                lineHeight: '1.5',
                color: '#1e293b'
              }}>
                {displayResultText || 'Summary generation completed successfully.'}
              </div>
              <button type="button" onClick={() => handleStartWithParams(true)} style={{ ...styles.buttonWarningSmall, marginTop: '12px' }}>
                Re-run Summary with Filters
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}