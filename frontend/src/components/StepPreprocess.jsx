import React from 'react';
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
  onNavigateToAudios
}) {
  const status = preprocessProgress ? preprocessProgress.status : 'IDLING';
  const isRunning = status === 'RUNNING';
  const isPaused = status === 'PAUSED';
  
  const isCompleted = status === 'COMPLETED' || (!preprocessProgress && isPreprocessFinished);
  
  const progressVal = preprocessProgress && preprocessProgress.progressPercentage != null 
    ? Math.round(preprocessProgress.progressPercentage) 
    : 0;

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
          {/* Universal Access to Audio and Image Tables (Available in any status) */}
          <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', marginBottom: '12px' }}>
            <button onClick={onNavigateToImages} style={styles.button}>📁 View Image Summaries</button>
            <button onClick={onNavigateToAudios} style={styles.button}>🎙️ View Audio Summaries</button>
          </div>

          {/* State: IDLING / Pending (Not started) */}
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

          {/* State: PAUSED (Aborted) - Keep progress bar statically, show one resume button */}
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

          {/* State: COMPLETED - No buttons, no progress bar, only sentence */}
          {isCompleted && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              <div style={{ fontSize: '0.85rem', color: '#059669', fontWeight: '600' }}>
                ✓ Preprocessing task finished successfully.
              </div>
            </div>
          )}
        </div>
      )}

      {errorPreprocess && <div style={styles.errorText}>⚠️ {errorPreprocess}</div>}
    </div>
  );
}