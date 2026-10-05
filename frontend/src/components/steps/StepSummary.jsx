import React, { useEffect, useState } from 'react';
import { styles } from '../../styles/dashboardStyles';
import { fromLocalInputValue } from '../../utils/time';
import useLanguage from '../../hooks/useLanguage';
import usePersonContext from '../../hooks/usePersonContext';
import PersonContextPanel from './PersonContextPanel';

export default function StepSummary({
  uuidInput,
  isPreprocessFinished,
  summaryState = {},
  handleStartSummary,
  handlePauseSummary,
  handleRestartSummary,
  loading = {},
  selectedStartTime,
  selectedEndTime,
  currentUser
}) {
  const { t } = useLanguage();
  const currentStatus = (summaryState?.status || 'INITIAL_STATE').toUpperCase();

  const isRunning = currentStatus === 'RUNNING';
  const isPaused = currentStatus === 'PAUSED';
  const isIdling = currentStatus === 'IDLING';
  const isFinished = currentStatus === 'SUCCESS' || currentStatus === 'COMPLETED';

  const person = usePersonContext({ uuidInput, currentUser, selectedStartTime, selectedEndTime });
  const [contextOpen, setContextOpen] = useState(false);
  const [confirming, setConfirming] = useState(false);

  // Load already-saved context (e.g. page refresh after a run) so the panel
  // is visible even when the summary already finished.
  useEffect(() => {
    if (uuidInput && currentUser && isPreprocessFinished) {
      person.fetchContext();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [uuidInput, currentUser, isPreprocessFinished]);

  const hasContext = (person.context?.people || []).length > 0;

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

  const handleExtract = async () => {
    setContextOpen(true);
    await person.extractContext();
  };

  const handleConfirmAndStart = async (isRestart = false) => {
    setConfirming(true);
    try {
      // Persist user-confirmed context first; the summary pipeline loads it
      // automatically for the same window, so no inline payload is needed.
      await person.saveContext(person.context);
    } finally {
      setConfirming(false);
    }
    handleStartWithParams(isRestart);
  };

  const rawResult = summaryState?.result;
  const displayResultText = typeof rawResult === 'string'
    ? rawResult
    : (rawResult ? JSON.stringify(rawResult, null, 2) : '');

  const canEditContext = !isRunning && !!uuidInput && isPreprocessFinished;

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

          {/* Person context section: visible before AND after the run, so the
              relationship view never disappears once the summary finishes.
              Editing is disabled while the engine is running. */}
          {canEditContext && !isRunning && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', marginBottom: isFinished ? '16px' : 0 }}>
              <div style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>
                {t('personContext.intro')}
              </div>
              <div style={styles.actionButtonRow}>
                <button
                  type="button"
                  onClick={handleExtract}
                  disabled={person.extracting || loading.start || confirming}
                  style={styles.buttonSecondary}
                >
                  {person.extracting ? t('personContext.extracting') : t('personContext.extract')}
                </button>
                <button
                  type="button"
                  onClick={() => setContextOpen(v => !v)}
                  style={styles.buttonSecondary}
                >
                  {contextOpen ? t('personContext.hide') : t('personContext.review')}
                  {hasContext ? ` (${person.context.people.length})` : ''}
                </button>
              </div>

              {person.error && (
                <div style={styles.dbErrorBox}>
                  {t('personContext.extractFailed')} {person.error}
                </div>
              )}

              {(contextOpen || hasContext) && (
                <PersonContextPanel
                  context={person.context}
                  onChange={person.setContext}
                  disabled={person.extracting || person.saving || confirming}
                />
              )}

              {(contextOpen || hasContext) && !isFinished && !isPaused && (
                <div style={styles.actionButtonRow}>
                  <button
                    type="button"
                    onClick={() => handleConfirmAndStart(false)}
                    disabled={loading.start || person.saving || confirming || person.extracting}
                    style={styles.button}
                  >
                    {(person.saving || confirming) ? t('personContext.saving') : t('personContext.confirmAndRun')}
                  </button>
                  <button
                    type="button"
                    onClick={() => handleStartWithParams(false)}
                    disabled={loading.start || confirming}
                    style={styles.buttonSecondary}
                  >
                    {loading.start ? t('summary.starting') : t('personContext.skipAndRun')}
                  </button>
                </div>
              )}

              {(contextOpen || hasContext) && (isFinished || isPaused) && (
                <div style={styles.actionButtonRow}>
                  <button
                    type="button"
                    onClick={() => handleConfirmAndStart(true)}
                    disabled={loading.restartSummary || person.saving || confirming || person.extracting}
                    style={styles.button}
                  >
                    {(person.saving || confirming) ? t('personContext.saving') : t('personContext.confirmAndRerun')}
                  </button>
                </div>
              )}

              {!contextOpen && !hasContext && !isFinished && !isPaused && (
                <button type="button" onClick={() => handleStartWithParams(false)} disabled={loading.start} style={styles.button}>
                  {loading.start ? t('summary.starting') : t('summary.run')}
                </button>
              )}
            </div>
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
