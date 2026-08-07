import React from 'react';
import { styles } from '../../styles/dashboardStyles';
import { fromLocalInputValue } from '../../utils/time';
import useLanguage from '../../hooks/useLanguage';

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
  const { t } = useLanguage();
  const currentStatus = (summaryState?.status || 'INITIAL_STATE').toUpperCase();

  const isRunning = currentStatus === 'RUNNING';
  const isPaused = currentStatus === 'PAUSED';
  const isIdling = currentStatus === 'IDLING';
  const isFinished = currentStatus === 'SUCCESS' || currentStatus === 'COMPLETED';

  const statusLabel = (() => {
    const map = {
      INITIAL_STATE: t('status.initial'),
      IDLING: t('status.idle'),
      RUNNING: t('status.running'),
      PAUSED: t('status.paused'),
      SUCCESS: t('status.success'),
      COMPLETED: t('status.completed'),
      FAILED: t('status.failed')
    };
    return map[currentStatus] || currentStatus;
  })();

  const rawProg = summaryState?.progress || 0;
  const progressFraction = rawProg > 1 ? rawProg / 100 : rawProg;
  const progressPercent = Math.round(progressFraction * 100);

  const handleStartWithParams = (isRestart = false) => {
    const payload = {};
    if (selectedStartTime) {
      payload.startTime = fromLocalInputValue(selectedStartTime);
    }
    if (selectedEndTime) {
      payload.endTime = fromLocalInputValue(selectedEndTime);
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
        <h3 style={styles.cardTitle}>{t('summary.title')}</h3>
        <span style={styles.lockBadge}>{statusLabel}</span>
      </div>

      {!uuidInput ? (
        <div style={styles.dbErrorBox}>{t('summary.selectDatasetFirst')}</div>
      ) : !isPreprocessFinished ? (
        <div style={styles.dbErrorBox}>{t('summary.completeStep2')}</div>
      ) : (
        <div style={styles.actionButtonGroup}>

          {(currentStatus === 'INITIAL_STATE' || isIdling) && (
            <button type="button" onClick={() => handleStartWithParams(false)} disabled={loading.start} style={styles.button}>
              {loading.start ? t('summary.starting') : t('summary.run')}
            </button>
          )}

          {isRunning && (
            <div style={styles.progressSection}>
              <div style={styles.progressLabelRow}>
                <span>{t('summary.generating')}</span>
                <span>{progressPercent}%</span>
              </div>
              <div style={styles.progressBarBg}>
                <div style={{ ...styles.progressBarFill, width: `${progressPercent}%`, backgroundColor: '#d97706' }} />
              </div>
              <button type="button" onClick={handlePauseSummary} disabled={loading.pauseSummary} style={styles.buttonWarningSmall}>
                {loading.pauseSummary ? t('summary.pausing') : t('summary.pause')}
              </button>
            </div>
          )}

          {isPaused && (
            <div style={styles.actionButtonRow}>
              <button type="button" onClick={() => handleStartWithParams(false)} disabled={loading.start} style={styles.button}>{t('summary.resume')}</button>
              <button type="button" onClick={() => handleStartWithParams(true)} disabled={loading.restartSummary} style={styles.buttonWarningSmall}>{t('summary.restart')}</button>
            </div>
          )}

          {(isFinished || displayResultText) && (
            <div style={styles.summaryContainer}>
              <div style={styles.summaryLabel}>{t('summary.finalLabel')}</div>
              <div style={{
                ...styles.cleanSummaryOutput,
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
                maxHeight: '400px',
                overflowY: 'auto',
                padding: '12px',
                background: 'var(--bg-card)',
                borderRadius: '6px',
                border: '1px solid var(--border)',
                fontSize: '14px',
                lineHeight: '1.5',
                color: 'var(--text-secondary)'
              }}>
                {displayResultText || t('summary.done')}
              </div>
              <button type="button" onClick={() => handleStartWithParams(true)} style={{ ...styles.buttonWarningSmall, marginTop: '12px' }}>
                {t('summary.rerun')}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}