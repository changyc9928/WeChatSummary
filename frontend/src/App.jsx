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
import useVideoSummaries from './hooks/useVideoSummaries';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import ImagesPage from './pages/ImagesPage';
import AudiosPage from './pages/AudiosPage';
import VideosPage from './pages/VideosPage';

export default function App() {
  const { theme, toggleTheme } = useTheme();
  const { currentUser, login, logout } = useAuth();

  const [file, setFile] = useState(null);
  const [uuidInput, setUuidInput] = useState('');
  const [currentView, setCurrentView] = useState('dashboard');
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
  const videos = useVideoSummaries({ uuidInput, currentUser });
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
    return <LoginPage onLoginSuccess={login} onToggleTheme={toggleTheme} theme={theme}/>;
  }

  const loading = {
    upload: upload.loading,
    preprocess: preprocess.loading,
    abortPreprocess: preprocess.aborting,
    reprocess: preprocess.reprocessing,
    start: summary.loading,
    pauseSummary: summary.pausing,
    restartSummary: summary.restarting
  };

  if (currentView === 'images') {
    return (
      <ImagesPage
        onBack={() => setCurrentView('dashboard')}
        onToggleTheme={toggleTheme}
        theme={theme}
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
        onBack={() => setCurrentView('dashboard')}
        onToggleTheme={toggleTheme}
        theme={theme}
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

  if (currentView === 'videos') {
    return (
      <VideosPage
        onBack={() => setCurrentView('dashboard')}
        onToggleTheme={toggleTheme}
        theme={theme}
        uuidInput={uuidInput}
        videos={{
          summaries: videos.videoSummaries,
          loading: videos.loadingVideos,
          pagination: videos.videoPagination,
          fetchSummaries: videos.fetchVideoSummaries,
          selectedIds: videos.selectedVideoIds,
          setSelectedIds: videos.setSelectedVideoIds,
          deleting: videos.deleting,
          clearingText: videos.clearingText,
          batchDeleting: videos.batchDeleting,
          batchClearingText: videos.batchClearingText,
          error: videos.error,
          deleteVideo: videos.deleteVideo,
          clearText: videos.clearVideoText,
          batchDelete: videos.batchDeleteVideos,
          batchClearText: videos.batchClearVideoTexts
        }}
        onRefreshProgress={(uuid) => preprocess.checkProgress(uuid)}
      />
    );
  }

  return (
    <DashboardPage
      currentUser={currentUser}
      fetchSessions={sessions.fetchSessions}
      file={file}
      loading={loading}
      loadingSessions={sessions.loadingSessions}
      onLogout={handleLogout}
      onNavigateToImages={() => setCurrentView('images')}
      onNavigateToAudios={() => setCurrentView('audios')}
      onNavigateToVideos={() => setCurrentView('videos')}
      onToggleTheme={toggleTheme}
      preprocess={{
        abortPreprocess: preprocess.abortPreprocess,
        isFinished: preprocess.isFinished,
        progress: preprocess.progress,
        reprocess: preprocess.reprocess,
        startPreprocess: preprocess.startPreprocess
      }}
      preprocessError={preprocess.error}
      sessions={sessions.sessions}
      setFile={setFile}
      setUuidInput={setUuidInput}
      summary={{
        pauseSummary: summary.pauseSummary,
        restartSummary: summary.restartSummary,
        startSummary: summary.startSummary,
        summaryState: summary.summaryState
      }}
      theme={theme}
      timeWindow={timeWindow}
      upload={upload}
      uuidInput={uuidInput}
      activeModalImage={activeModalImage}
      setActiveModalImage={setActiveModalImage}
    />
  );
}
