import React, { useState } from 'react';
import { styles } from '../styles/dashboardStyles';
import DatasetSelector from '../components/panels/DatasetSelector';
import StepUpload from '../components/steps/StepUpload';
import StepPreprocess from '../components/steps/StepPreprocess';
import StepSummary from '../components/steps/StepSummary';
import ThemeToggle from '../components/common/ThemeToggle';
import LanguageToggle from '../components/common/LanguageToggle';
import ImageLightboxModal from '../components/common/ImageLightboxModal';
import LocalScanPanel from '../components/panels/LocalScanPanel';
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
  deleteSession,
  loading,
  preprocess,
  preprocessError,
  timeWindow,
  summary,
  onNavigateToImages,
  onNavigateToAudios,
  onNavigateToVideos,
  onNavigateToEmojis,
  onFetchImages,
  onFetchAudios,
  onFetchVideos,
  onFetchEmojis,
  activeModalImage,
  setActiveModalImage
}) {
  const { t } = useLanguage();
  // The local export bridge is collapsed by default to keep the page clean.
  // The dataset picker stays visible below it.
  const [setupOpen, setSetupOpen] = useState(false);

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

      {/* Setup (collapsible, collapsed by default): local export bridge */}
      <div style={{ ...styles.card, marginBottom: '20px' }}>
        <button
          type="button"
          onClick={() => setSetupOpen(v => !v)}
          aria-expanded={setupOpen}
          style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '10px', flexWrap: 'wrap', background: 'none', border: 'none', padding: 0, cursor: 'pointer', textAlign: 'left', width: '100%' }}
        >
          <span style={{ margin: 0, fontSize: '1.1rem', fontWeight: '700', color: 'var(--text-primary)' }}>
            {setupOpen ? '▾' : '▸'} {t('dashboard.setupTitle')}
          </span>
          <span style={{ fontSize: '0.75rem', fontWeight: '600', color: 'var(--text-muted)' }}>
            {setupOpen ? t('localBridge.collapse') : t('localBridge.expand')}
          </span>
        </button>
        {!setupOpen && (
          <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>{t('dashboard.setupHint')}</span>
        )}

        {setupOpen && (
          <div style={{ width: '100%', display: 'block' }}>
            <LocalScanPanel />
          </div>
        )}
      </div>

      <DatasetSelector
        deleteSession={deleteSession}
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
          handleUpload={upload.handleUpload}
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
          handleRestartPreprocess={preprocess.restartPreprocess}
          handleStartPreprocess={preprocess.startPreprocess}
          isPreprocessFinished={preprocess.isFinished}
          loading={loading}
          onFetchAudios={onFetchAudios}
          onFetchEmojis={onFetchEmojis}
          onFetchImages={onFetchImages}
          onFetchVideos={onFetchVideos}
          onNavigateToAudios={onNavigateToAudios}
          onNavigateToImages={onNavigateToImages}
          onNavigateToVideos={onNavigateToVideos}
          onNavigateToEmojis={onNavigateToEmojis}
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
