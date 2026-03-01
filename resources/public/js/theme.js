(function() {
  const STORAGE_KEY = 'datalevin-theme';
  const DARK = 'dark';
  const LIGHT = 'light';

  function getPreferredTheme() {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored) return stored;
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? DARK : LIGHT;
  }

  function setTheme(theme) {
    const body = document.body;
    const header = document.querySelector('header');
    const themeToggle = document.getElementById('theme-toggle');

    if (theme === LIGHT) {
      body.classList.remove(DARK);
      body.classList.add(LIGHT);
      if (header) {
        header.setAttribute('data-theme', 'light');
        header.style.background = 'rgba(248,250,252,0.9)';
        header.style.borderBottomColor = 'rgba(0,0,0,0.1)';
      }
      if (themeToggle) {
        themeToggle.style.background = 'rgba(0,0,0,0.05)';
        themeToggle.style.borderColor = 'rgba(0,0,0,0.15)';
        themeToggle.style.color = '#475569';
      }
    } else {
      body.classList.remove(LIGHT);
      body.classList.add(DARK);
      if (header) {
        header.setAttribute('data-theme', 'dark');
        header.style.background = 'rgba(10,10,15,0.8)';
        header.style.borderBottomColor = 'rgba(255,255,255,0.1)';
      }
      if (themeToggle) {
        themeToggle.style.background = 'rgba(255,255,255,0.1)';
        themeToggle.style.borderColor = 'rgba(255,255,255,0.15)';
        themeToggle.style.color = '#9ca3af';
      }
    }

    localStorage.setItem(STORAGE_KEY, theme);
  }

  function initTheme() {
    const theme = getPreferredTheme();
    setTheme(theme);
  }

  window.toggleTheme = function() {
    const current = localStorage.getItem(STORAGE_KEY);
    const newTheme = current === LIGHT ? DARK : LIGHT;
    setTheme(newTheme);
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initTheme);
  } else {
    initTheme();
  }
})();
