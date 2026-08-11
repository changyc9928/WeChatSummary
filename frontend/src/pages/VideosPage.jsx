import React from 'react';
import { styles } from '../styles/dashboardStyles';
import ThemeToggle from '../components/common/ThemeToggle';
import LanguageToggle from '../components/common/LanguageToggle';
import VideoSummariesPanel from '../components/panels/VideoSummariesPanel';
import useLanguage from '../hooks/useLanguage';

export default function VideosPage({
  theme,
  onToggleTheme,
  onBack,
  uuidInput,
  videos,
  onRefreshProgress
}) {
  const { t } = useLanguage();
  const handleDeleteVideo = async (id) => {
    await videos.deleteVideo(id);
    if (uuidInput) await onRefreshProgress(uuidInput);
  };

  const handleClearVideoText = async (id) => {
    await videos.clearText(id);
    if (uuidInput) await onRefreshProgress(uuidInput);
  };

  const handleBatchDeleteVideos = async () => {
    if (videos.selectedIds.length === 0) return;
    await videos.batchDelete(videos.selectedIds);
    if (uuidInput) await onRefreshProgress(uuidInput);
  };

  const handleBatchClearVideoTexts = async () => {
    if (videos.selectedIds.length === 0) return;
    await videos.batchClearText(videos.selectedIds);
    if (uuidInput) await onRefreshProgress(uuidInput);
  };

  const loading = {
    deleteVideo: videos.deleting,
    clearVideoText: videos.clearingText,
    batchDeleteVideos: videos.batchDeleting,
    batchClearVideoTexts: videos.batchClearingText
  };

  return (
    <div style={styles.container}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '15px' }}>
        <h2 style={styles.title}>{t('videos.title')}</h2>
        <div style={{ display: 'flex', gap: '8px' }}>
          <LanguageToggle />
          <ThemeToggle theme={theme} onToggle={onToggleTheme} />
          <button onClick={onBack} style={styles.buttonSecondary}>{t('common.back')}</button>
        </div>
      </div>
      <VideoSummariesPanel
        uuidInput={uuidInput}
        videoSummaries={videos.summaries}
        loadingVideos={videos.loading}
        videoPagination={videos.pagination}
        fetchVideoSummaries={videos.fetchSummaries}
        selectedVideoIds={videos.selectedIds}
        setSelectedVideoIds={videos.setSelectedIds}
        handleDeleteVideo={handleDeleteVideo}
        handleClearVideoText={handleClearVideoText}
        handleBatchDeleteVideos={handleBatchDeleteVideos}
        handleBatchClearVideoTexts={handleBatchClearVideoTexts}
        loading={loading}
        errorVideos={videos.error}
      />
    </div>
  );
}
