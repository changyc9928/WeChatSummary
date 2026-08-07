import { useEffect, useState } from 'react';

export default function useTheme() {
  const [theme, setTheme] = useState(() => localStorage.getItem('wechat_theme') || 'light');

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('wechat_theme', theme);
  }, [theme]);

  const toggleTheme = () => setTheme(prev => (prev === 'dark' ? 'light' : 'dark'));

  return { theme, toggleTheme };
}