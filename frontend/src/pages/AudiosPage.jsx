import React from 'react';
import { styles } from '../styles/dashboardStyles';
import ThemeToggle from '../components/common/ThemeToggle';
import AudioSummariesPanel from '../components/panels/AudioSummariesPanel';

export default function AudiosPage({
  theme,
  onToggleTheme,
  onBack,
  uuidInput,
  audios,
  onRefreshProgress
}) {
  const handleDeleteAudio = async (id) => {
    await audios.deleteAudio(id);
    if (uuidInput) await onRefreshProgress(uuidInput);
  };

  const handleClearAudioText = async (id) => {
    await audios.clearText(id);
    if (uuidInput) await onRefreshProgress(uuidInput);
  };

  const handleBatchDeleteAudios = async () => {
    if (audios.selectedIds.length === 0) return;
    await audios.batchDelete(audios.selectedIds);
    if (uuidInput) await onRefreshProgress(uuidInput);
  };

  const handleBatchClearAudioTexts = async () => {
    if (audios.selectedIds.length === 0) return;
    await audios.batchClearText(audios.selectedIds);
    if (uuidInput) await onRefreshProgress(uuidInput);
  };

  const loading = {
    deleteAudio: audios.deleting,
    clearAudioText: audios.clearingText,
    batchDeleteAudios: audios.batchDeleting,
    batchClearAudioTexts: audios.batchClearingText
  };

  return (
    <div style={styles.container}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '15px' }}>
        <h2 style={styles.title}>Audio Transcripts & Summaries</h2>
        <div style={{ display: 'flex', gap: '8px' }}>
          <ThemeToggle theme={theme} onToggle={onToggleTheme} />
          <button onClick={onBack} style={styles.buttonSecondary}>← Back to Dashboard</button>
        </div>
      </div>
      <AudioSummariesPanel
        uuidInput={uuidInput}
        audioSummaries={audios.summaries}
        loadingAudios={audios.loading}
        audioPagination={audios.pagination}
        fetchAudioSummaries={audios.fetchSummaries}
        selectedAudioIds={audios.selectedIds}
        setSelectedAudioIds={audios.setSelectedIds}
        handleDeleteAudio={handleDeleteAudio}
        handleClearAudioText={handleClearAudioText}
        handleBatchDeleteAudios={handleBatchDeleteAudios}
        handleBatchClearAudioTexts={handleBatchClearAudioTexts}
        loading={loading}
        errorAudios={audios.error}
      />
    </div>
  );
}