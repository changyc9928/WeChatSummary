import React from 'react';
import { styles } from '../styles/dashboardStyles';
import ThemeToggle from '../components/common/ThemeToggle';
import LanguageToggle from '../components/common/LanguageToggle';
import EmojiSummariesPanel from '../components/panels/EmojiSummariesPanel';
import ImageLightboxModal from '../components/common/ImageLightboxModal';
import useLanguage from '../hooks/useLanguage';

export default function EmojisPage({
  theme,
  onToggleTheme,
  onBack,
  uuidInput,
  currentUser,
  emojis,
  onRefreshProgress,
  activeModalEmoji,
  setActiveModalEmoji
}) {
  const { t } = useLanguage();
  const handleDeleteEmoji = async (id) => {
    await emojis.deleteEmoji(id);
    if (uuidInput) await onRefreshProgress(uuidInput);
  };

  const handleBatchDeleteEmojis = async () => {
    if (emojis.selectedEmojiIds.length === 0) return;
    await emojis.batchDeleteEmojis(emojis.selectedEmojiIds);
    if (uuidInput) await onRefreshProgress(uuidInput);
  };

  const loading = {
    deleteEmoji: emojis.deleting,
    batchDeleteEmojis: emojis.batchDeleting
  };

  return (
    <div style={styles.container}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '15px' }}>
        <h2 style={styles.title}>{t('emojis.title')}</h2>
        <div style={{ display: 'flex', gap: '8px' }}>
          <LanguageToggle />
          <ThemeToggle theme={theme} onToggle={onToggleTheme} />
          <button onClick={onBack} style={styles.buttonSecondary}>{t('common.back')}</button>
        </div>
      </div>
      <EmojiSummariesPanel
        uuidInput={uuidInput}
        emojiSummaries={emojis.emojiSummaries}
        loadingEmojis={emojis.loadingEmojis}
        emojiPagination={emojis.emojiPagination}
        fetchEmojiSummaries={emojis.fetchEmojiSummaries}
        selectedEmojiIds={emojis.selectedEmojiIds}
        setSelectedEmojiIds={emojis.setSelectedEmojiIds}
        handleDeleteEmoji={handleDeleteEmoji}
        handleBatchDeleteEmojis={handleBatchDeleteEmojis}
        loading={loading}
        errorEmojis={emojis.error}
        currentUser={currentUser}
        setActiveModalEmoji={setActiveModalEmoji}
      />
      <ImageLightboxModal activeModalImage={activeModalEmoji} setActiveModalImage={setActiveModalEmoji} />
    </div>
  );
}
