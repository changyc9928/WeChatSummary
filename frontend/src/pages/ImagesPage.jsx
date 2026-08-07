import React from 'react';
import { styles } from '../styles/dashboardStyles';
import ThemeToggle from '../components/common/ThemeToggle';
import LanguageToggle from '../components/common/LanguageToggle';
import ImageSummariesPanel from '../components/panels/ImageSummariesPanel';
import ImageLightboxModal from '../components/common/ImageLightboxModal';
import useLanguage from '../hooks/useLanguage';

export default function ImagesPage({
  theme,
  onToggleTheme,
  onBack,
  uuidInput,
  currentUser,
  images,
  onRefreshProgress,
  activeModalImage,
  setActiveModalImage
}) {
  const { t } = useLanguage();
  const handleDeleteImage = async (id) => {
    await images.deleteImage(id);
    if (uuidInput) await onRefreshProgress(uuidInput);
  };

  const handleBatchDeleteImages = async () => {
    if (images.selectedImageIds.length === 0) return;
    await images.batchDelete(images.selectedImageIds);
    if (uuidInput) await onRefreshProgress(uuidInput);
  };

  const loading = {
    deleteImage: images.deleting,
    batchDeleteImages: images.batchDeleting
  };

  return (
    <div style={styles.container}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '15px' }}>
        <h2 style={styles.title}>{t('images.title')}</h2>
        <div style={{ display: 'flex', gap: '8px' }}>
          <LanguageToggle />
          <ThemeToggle theme={theme} onToggle={onToggleTheme} />
          <button onClick={onBack} style={styles.buttonSecondary}>{t('common.back')}</button>
        </div>
      </div>
      <ImageSummariesPanel
        uuidInput={uuidInput}
        imageSummaries={images.summaries}
        loadingImages={images.loading}
        imagePagination={images.pagination}
        fetchImageSummaries={images.fetchSummaries}
        selectedImageIds={images.selectedIds}
        setSelectedImageIds={images.setSelectedIds}
        handleDeleteImage={handleDeleteImage}
        handleBatchDeleteImages={handleBatchDeleteImages}
        setActiveModalImage={setActiveModalImage}
        loading={loading}
        errorImages={images.error}
        currentUser={currentUser}
      />
      <ImageLightboxModal activeModalImage={activeModalImage} setActiveModalImage={setActiveModalImage} />
    </div>
  );
}