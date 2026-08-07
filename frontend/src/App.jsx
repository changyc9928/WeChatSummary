import React, { useState } from 'react';
import useTheme from './hooks/useTheme';
import useAuth from './hooks/useAuth';
import useSessions from './hooks/useSessions';
import useTimeWindow from './hooks/useTimeWindow';
import useUpload from './hooks/useUpload';
import usePreprocess from './hooks/usePreprocess';
import useSummaryStatus from './hooks/useSummaryStatus';
import useImageSummaries from './hooks/useImageSummaries';
import useAudioSummaries from './hooks/useAudioSummaries';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import ImagesPage from './pages/ImagesPage';
import AudiosPage from './pages/AudiosPage';

export default function App() {
  const { theme, toggleTheme } = useTheme();
  const { currentUser, login, logout } = useAuth();

  const [file, setFile] = useState(null);
  const [uuidInput, setUuidInput] = useState('');
  const [currentView, setCurrentView] = useState('dashboard'); // 'dashboard' | 'images' | 'audios'
  const [activeModalImage, setActiveModalImage] = useState(null);

  const sessions = useSessions(currentUser);
  const timeWindow = useTimeWindow(uuidInput);
  const summary = useSummaryStatus({ uuidInput, currentUser });
  const preprocess = usePreprocess({
    uuidInput,
    currentUser,
    onCompleted: (uuid) => summary.fetchStatus(uuid)
  });
  const images = useImageSummaries({ uuidInput, currentUser });
  const audios = useAudioSummaries({ uuidInput, currentUser });
  const upload = useUpload({
    currentUser,
    onUploaded: (assignedUuid) => {
      sessions.fetchSessions();
      setUuidInput(assignedUuid);
    }
  });

  const handleLogout = () => {
    logout();
    setUuidInput('');
    setCurrentView('dashboard');
  };

  if (!currentUser) {
    return <LoginPage theme={theme} onToggleTheme={toggleTheme} onLoginSuccess={login} />;
  }

  const loading = {
    upload: upload.loading,
    preprocess: preprocess.loading,
    abortPreprocess: preprocess.aborting,
    start: summary.loading,
    pauseSummary: summary.pausing,
    restartSummary: summary.restarting
  };

  if (currentView === 'images') {
    return (
      <ImagesPage
        theme={theme}
        onToggleTheme={toggleTheme}
        onBack={() => setCurrentView('dashboard')}
        uuidInput={uuidInput}
        currentUser={currentUser}
        images={{
          summaries: images.imageSummaries,
          loading: images.loadingImages,
          pagination: images.imagePagination,
          fetchSummaries: images.fetchImageSummaries,
          selectedIds: images.selectedImageIds,
          setSelectedIds: images.setSelectedImageIds,
          deleting: images.deleting,
          batchDeleting: images.batchDeleting,
          error: images.error,
          deleteImage: images.deleteImage,
          batchDelete: images.batchDeleteImages
        }}
        onRefreshProgress={(uuid) => preprocess.checkProgress(uuid)}
        activeModalImage={activeModalImage}
        setActiveModalImage={setActiveModalImage}
      />
    );
  }

  if (currentView === 'audios') {
    return (
      <AudiosPage
        theme={theme}
        onToggleTheme={toggleTheme}
        onBack={() => setCurrentView('dashboard')}
        uuidInput={uuidInput}
        audios={{
          summaries: audios.audioSummaries,
          loading: audios.loadingAudios,
          pagination: audios.audioPagination,
          fetchSummaries: audios.fetchAudioSummaries,
          selectedIds: audios.selectedAudioIds,
          setSelectedIds: audios.setSelectedAudioIds,
          deleting: audios.deleting,
          clearingText: audios.clearingText,
          batchDeleting: audios.batchDeleting,
          batchClearingText: audios.batchClearingText,
          error: audios.error,
          deleteAudio: audios.deleteAudio,
          clearText: audios.clearAudioText,
          batchDelete: audios.batchDeleteAudios,
          batchClearText: audios.batchClearAudioTexts
        }}
        onRefreshProgress={(uuid) => preprocess.checkProgress(uuid)}
      />
    );
  }

  return (
    <DashboardPage
      theme={theme}
      onToggleTheme={toggleTheme}
      currentUser={currentUser}
      onLogout={handleLogout}
      file={file}
      setFile={setFile}
      upload={upload}
      uuidInput={uuidInput}
      setUuidInput={setUuidInput}
      sessions={sessions.sessions}
      loadingSessions={sessions.loadingSessions}
      fetchSessions={sessions.fetchSessions}
      loading={loading}
      preprocess={{
        isFinished: preprocess.isFinished,
        progress: preprocess.progress,
        onStart: preprocess.startPreprocess,
        onAbort: preprocess.abortPreprocess
      }}
      preprocessError={preprocess.error}
      timeWindow={timeWindow}
      summary={{
        state: summary.summaryState,
        onStart: summary.startSummary,
        onPause: summary.pauseSummary,
        onRestart: summary.restartSummary
      }}
      onNavigateToImages={() => setCurrentView('images')}
      onNavigateToAudios={() => setCurrentView('audios')}
      activeModalImage={activeModalImage}
      setActiveModalImage={setActiveModalImage}
    />
  );
}