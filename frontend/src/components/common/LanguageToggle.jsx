import useLanguage from '../../hooks/useLanguage';

// eslint-disable-next-line react/prop-types
export default function LanguageToggle({ style }) {
  const { lang, toggleLang, t } = useLanguage();
  const isZh = lang === 'zh';
  return (
    <button
      type="button"
      onClick={toggleLang}
      style={{
        backgroundColor: 'transparent',
        border: '1px solid var(--border-strong)',
        color: 'var(--text-secondary)',
        padding: '6px 10px',
        borderRadius: '6px',
        fontSize: '0.78rem',
        fontWeight: '600',
        cursor: 'pointer',
        ...style
      }}
      title={isZh ? t('lang.toggleTitleToEn') : t('lang.toggleTitleToZh')}
    >
      {isZh ? 'EN' : '中文'}
    </button>
  );
}