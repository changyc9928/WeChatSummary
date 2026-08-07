import React from 'react';
import { styles } from '../styles/dashboardStyles';
import DatasetSelector from '../components/panels/DatasetSelector';
import StepUpload from '../components/steps/StepUpload';
import StepPreprocess from '../components/steps/StepPreprocess';
import StepSummary from '../components/steps/StepSummary';
import ThemeToggle from '../components/common/ThemeToggle';
import ImageLightboxModal from '../components/common/ImageLightboxModal';

export default function DashboardPage({
  theme,
  onToggleTheme,
  currentUser,
  onLogout,
  file,
  setFile,
  upload,
  uuidInput,
  setUuidInput,
  sessions,
  loadingSessions,
  fetchSessions,
  loading,
  preprocess,
  preprocessError,
  timeWindow,
  summary,
  onNavigateToImages,
  onNavigateToAudios,
  activeModalImage,
  setActiveModalImage
}) {
  return (
    <div style={styles.container}>
      <header style={styles.header}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '10px' }}>
          <div>
            <h1 style={{ ...styles.title, margin: 0, textAlign: 'left' }}>Data Summary Dashboard</h1>
            <p style={{ ...styles.subtitle, textAlign: 'left', margin: '4px 0 0 0' }}>
              Logged in as: <strong>{currentUser.username}</strong>
            </p>
          </div>
          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <ThemeToggle theme={theme} onToggle={onToggleTheme} />
            <button onClick={onLogout} style={styles.buttonDangerSmall}>Logout</button>
          </div>
        </div>
      </header>

      <DatasetSelector
        uuidInput={uuidInput}
        setUuidInput={setUuidInput}
        sessions={sessions}
        loadingSessions={loadingSessions}
        fetchSessions={fetchSessions}
      />

      {/* Row 1: Step 1 (Full Width) */}
      <div style={{ width: '100%', display: 'block', marginBottom: '20px' }}>
        <StepUpload
          file={file}
          setFile={setFile}
          handleUpload={upload.handle}
          loading={loading.upload}
          errorUpload={upload.error}
        />
      </div>

      {/* Row 2: Step 2 (Full Width) */}
      <div style={{ width: '100%', display: 'block', marginBottom: '20px' }}>
        <StepPreprocess
          uuidInput={uuidInput}
          isPreprocessFinished={preprocess.isFinished}
          preprocessProgress={preprocess.progress}
          handleStartPreprocess={preprocess.onStart}
          handleAbortPreprocess={preprocess.onAbort}
          loading={loading}
          errorPreprocess={preprocessError}
          onNavigateToImages={onNavigateToImages}
          onNavigateToAudios={onNavigateToAudios}
          currentUser={currentUser}
          selectedStartTime={timeWindow.selectedStartTime}
          setSelectedStartTime={timeWindow.setSelectedStartTime}
          selectedEndTime={timeWindow.selectedEndTime}
          setSelectedEndTime={timeWindow.setSelectedEndTime}
        />
      </div>

      {/* Row 3: Step 3 (Full Width) */}
      <div style={{ width: '100%', display: 'block' }}>
        <StepSummary
          uuidInput={uuidInput}
          isPreprocessFinished={preprocess.isFinished}
          summaryState={summary.state}
          handleStartSummary={summary.onStart}
          handlePauseSummary={summary.onPause}
          handleRestartSummary={summary.onRestart}
          loading={loading}
          selectedStartTime={timeWindow.selectedStartTime}
          selectedEndTime={timeWindow.selectedEndTime}
        />
      </div>

      <ImageLightboxModal activeModalImage={activeModalImage} setActiveModalImage={setActiveModalImage} />
    </div>
  );
}