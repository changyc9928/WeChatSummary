import { useEffect, useMemo, useState } from 'react';
import { createT, translations } from '../i18n/translations';
import { LanguageContext } from './languageContext';

const STORAGE_KEY = 'wechat_language';

// eslint-disable-next-line react/prop-types
export default function LanguageProvider({ children }) {
  const [lang, setLang] = useState(() => localStorage.getItem(STORAGE_KEY) || 'en');

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, lang);
    document.documentElement.setAttribute('lang', lang);
    document.title = translations[lang]?.['app.title'] || translations.en['app.title'];
  }, [lang]);

  const t = useMemo(() => createT(lang), [lang]);

  const toggleLang = () => setLang(prev => (prev === 'zh' ? 'en' : 'zh'));

  return (
    <LanguageContext.Provider value={{ lang, setLang, toggleLang, t }}>
      {children}
    </LanguageContext.Provider>
  );
}