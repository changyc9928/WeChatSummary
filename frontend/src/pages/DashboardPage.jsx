import React from 'react';
import { styles } from '../styles/dashboardStyles';
import DatasetSelector from '../components/panels/DatasetSelector';
import StepUpload from '../components/steps/StepUpload';
import StepPreprocess from '../components/steps/StepPreprocess';
import StepSummary from '../components/steps/StepSummary';
import ThemeToggle from '../components/common/ThemeToggle';
import LanguageToggle from '../components/common/LanguageToggle';
import ImageLightboxModal from '../components/common/ImageLightboxModal';
import useLanguage from '../hooks/useLanguage';

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
  onNavigateToVideos,
  activeModalImage,
  setActiveModalImage
}) {
  const { t } = useLanguage();

  return (
    <div style={styles.container}>
      <header style={styles.header}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '10px' }}>
          <div>
            <h1 style={{ ...styles.title, margin: 0, textAlign: 'left' }}>{t('dashboard.title')}</h1>
            <p style={{ ...styles.subtitle, textAlign: 'left', margin: '4px 0 0 0' }}>
              {t('dashboard.loggedInAs')} <strong>{currentUser.username}</strong>
            </p>
          </div>
          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <LanguageToggle/>
            <ThemeToggle onToggle={onToggleTheme} theme={theme}/>
            <button onClick={onLogout} style={styles.buttonDangerSmall}>{t('dashboard.logout')}</button>
          </div>
        </div>
      </header>

      <DatasetSelector
        fetchSessions={fetchSessions}
        loadingSessions={loadingSessions}
        sessions={sessions}
        setUuidInput={setUuidInput}
        uuidInput={uuidInput}
      />

      {/* Row 1: Step 1 (Full Width) */}
      <div style={{ width: '100%', display: 'block', marginBottom: '20px' }}>
        <StepUpload
          errorUpload={upload.error}
          file={file}
          handleUpload={upload.handle}
          loadingUpload={loading.upload}
          setFile={setFile}
        />
      </div>

      {/* Row 2: Step 2 (Full Width) */}
      <div style={{ width: '100%', display: 'block', marginBottom: '20px' }}>
        <StepPreprocess
          currentUser={currentUser}
          errorPreprocess={preprocessError}
          handleAbortPreprocess={preprocess.abortPreprocess}
          handleReprocessPreprocess={preprocess.reprocess}
          handleStartPreprocess={preprocess.startPreprocess}
          isPreprocessFinished={preprocess.isFinished}
          loading={loading}
          onNavigateToAudios={onNavigateToAudios}
          onNavigateToImages={onNavigateToImages}
          onNavigateToVideos={onNavigateToVideos}
          preprocessProgress={preprocess.progress}
          selectedEndTime={timeWindow.selectedEndTime}
          selectedStartTime={timeWindow.selectedStartTime}
          setSelectedEndTime={timeWindow.setSelectedEndTime}
          setSelectedStartTime={timeWindow.setSelectedStartTime}
          uuidInput={uuidInput}
        />
      </div>

      {/* Row 3: Step 3 (Full Width) */}
      <div style={{ width: '100%', display: 'block' }}>
        <StepSummary
          handlePauseSummary={summary.pauseSummary}
          handleRestartSummary={summary.restartSummary}
          handleStartSummary={summary.startSummary}
          isPreprocessFinished={preprocess.isFinished}
          loading={loading}
          selectedEndTime={timeWindow.selectedEndTime}
          selectedStartTime={timeWindow.selectedStartTime}
          summaryState={summary.summaryState}
          uuidInput={uuidInput}
        />
      </div>

      <ImageLightboxModal activeModalImage={activeModalImage} setActiveModalImage={setActiveModalImage} />
    </div>
  );
}
