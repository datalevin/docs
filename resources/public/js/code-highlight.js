// Highlight.js init + rainbow parentheses for Clojure + multi-lang tabs
(function() {
  var PAREN_COLORS = [
    '#e6c07b', // gold
    '#c678dd', // purple
    '#56b6c2', // cyan
    '#61afef', // blue
    '#e06c75', // red
    '#98c379', // green
  ];

  var LANG_LABELS = {
    'clojure': 'Clojure',
    'clj': 'Clojure',
    'java': 'Java',
    'python': 'Python',
    'py': 'Python',
    'javascript': 'JavaScript',
    'js': 'JavaScript',
  };

  var LANG_ALIASES = {
    'clj': 'clojure',
    'py': 'python',
    'js': 'javascript',
  };

  var tabGroupId = 0;

  function rainbowParens(el) {
    var html = el.innerHTML;
    var depth = 0;
    var out = [];
    var i = 0;
    while (i < html.length) {
      // Skip HTML tags
      if (html[i] === '<') {
        var end = html.indexOf('>', i);
        if (end !== -1) {
          out.push(html.substring(i, end + 1));
          i = end + 1;
          continue;
        }
      }
      // Skip HTML entities
      if (html[i] === '&') {
        var semi = html.indexOf(';', i);
        if (semi !== -1 && semi - i < 10) {
          out.push(html.substring(i, semi + 1));
          i = semi + 1;
          continue;
        }
      }
      var ch = html[i];
      if (ch === '(' || ch === '[' || ch === '{') {
        var color = PAREN_COLORS[depth % PAREN_COLORS.length];
        out.push('<span class="rainbow-paren" style="color:' + color + '">' + ch + '</span>');
        depth++;
      } else if (ch === ')' || ch === ']' || ch === '}') {
        depth = Math.max(0, depth - 1);
        var color = PAREN_COLORS[depth % PAREN_COLORS.length];
        out.push('<span class="rainbow-paren" style="color:' + color + '">' + ch + '</span>');
      } else {
        out.push(ch);
      }
      i++;
    }
    el.innerHTML = out.join('');
  }

  function canonicalLang(lang) {
    var normalized = (lang || '').toLowerCase();
    return LANG_ALIASES[normalized] || normalized;
  }

  function getLang(codeEl) {
    var cls = codeEl.className || '';
    var m = cls.match(/(?:^|\s)language-([^\s]+)/);
    return m ? canonicalLang(m[1]) : '';
  }

  function isClojureBlock(codeEl) {
    var cls = codeEl.className || '';
    return /\blanguage-(clojure|clj|edn)\b/.test(cls) ||
      /\b(clojure|clj|edn)\b/.test(cls);
  }

  function fixClojurePredicateSymbols(el) {
    var symbols = el.querySelectorAll('.hljs-symbol');
    for (var i = 0; i < symbols.length; i++) {
      var symbol = symbols[i];
      var text = symbol.textContent || '';
      if (text.charAt(0) !== ':') continue;

      var next = symbol.nextSibling;
      if (!next || next.nodeType !== 3) continue;

      var nextText = next.nodeValue || '';
      var match = nextText.match(/^\?[^\s\[\]\(\)\{\}",;]*/);
      if (!match) continue;

      symbol.textContent = text + match[0];
      next.nodeValue = nextText.slice(match[0].length);
    }
  }

  function activatePanel(container, idx) {
    var tabs = container.querySelectorAll('.lang-tab');
    var panels = container.querySelectorAll('.lang-panel');
    for (var t = 0; t < tabs.length; t++) {
      var active = t === idx;
      tabs[t].classList.toggle('active', active);
      tabs[t].setAttribute('aria-selected', active ? 'true' : 'false');
      tabs[t].setAttribute('tabindex', active ? '0' : '-1');
    }
    for (var p = 0; p < panels.length; p++) {
      var panelActive = p === idx;
      panels[p].classList.toggle('active', panelActive);
      panels[p].hidden = !panelActive;
    }
  }

  function buildMultiLangTabs() {
    var containers = document.querySelectorAll('.multi-lang:not([data-tabs-built])');
    for (var c = 0; c < containers.length; c++) {
      var container = containers[c];
      var pres = container.querySelectorAll('pre');
      if (pres.length < 2) continue;
      var groupId = ++tabGroupId;

      // Build tab bar
      var tabBar = document.createElement('div');
      tabBar.className = 'lang-tabs';
      tabBar.setAttribute('role', 'tablist');
      tabBar.setAttribute('aria-label', 'Code language');

      var panels = [];
      for (var p = 0; p < pres.length; p++) {
        var pre = pres[p];
        var code = pre.querySelector('code');
        var lang = code ? getLang(code) : '';
        var label = LANG_LABELS[lang] || lang || ('Tab ' + (p + 1));
        var tabId = 'lang-tab-' + groupId + '-' + p;
        var panelId = 'lang-panel-' + groupId + '-' + p;

        // Create tab button
        var tab = document.createElement('button');
        tab.className = 'lang-tab' + (p === 0 ? ' active' : '');
        tab.type = 'button';
        tab.id = tabId;
        tab.textContent = label;
        tab.setAttribute('data-lang', lang);
        tab.setAttribute('data-index', p);
        tab.setAttribute('role', 'tab');
        tab.setAttribute('aria-controls', panelId);
        tab.setAttribute('aria-selected', p === 0 ? 'true' : 'false');
        tab.setAttribute('tabindex', p === 0 ? '0' : '-1');
        tabBar.appendChild(tab);

        // Wrap pre in a panel div
        var panel = document.createElement('div');
        panel.className = 'lang-panel' + (p === 0 ? ' active' : '');
        panel.id = panelId;
        panel.setAttribute('data-lang', lang);
        panel.setAttribute('role', 'tabpanel');
        panel.setAttribute('aria-labelledby', tabId);
        panel.hidden = p !== 0;
        pre.parentNode.insertBefore(panel, pre);
        panel.appendChild(pre);
        panels.push(panel);
      }

      // Insert tab bar before first panel
      container.insertBefore(tabBar, panels[0]);

      // Tab click handler
      tabBar.addEventListener('click', function(e) {
        var btn = e.target.closest('.lang-tab');
        if (!btn) return;
        var idx = parseInt(btn.getAttribute('data-index'), 10);
        var parent = btn.closest('.multi-lang');
        activatePanel(parent, idx);
        btn.focus();

        // Remember selected language globally
        var lang = canonicalLang(btn.getAttribute('data-lang'));
        if (lang) {
          try { localStorage.setItem('dl-lang', lang); } catch(e) {}
          syncAllTabs(lang);
        }
      });

      tabBar.addEventListener('keydown', function(e) {
        if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft' && e.key !== 'Home' && e.key !== 'End') {
          return;
        }
        var current = e.target.closest('.lang-tab');
        if (!current) return;
        var parent = current.closest('.multi-lang');
        var tabs = parent.querySelectorAll('.lang-tab');
        var idx = parseInt(current.getAttribute('data-index'), 10);
        if (e.key === 'ArrowRight') {
          idx = (idx + 1) % tabs.length;
        } else if (e.key === 'ArrowLeft') {
          idx = (idx + tabs.length - 1) % tabs.length;
        } else if (e.key === 'Home') {
          idx = 0;
        } else if (e.key === 'End') {
          idx = tabs.length - 1;
        }
        e.preventDefault();
        tabs[idx].click();
      });

      container.setAttribute('data-tabs-built', 'true');
    }
  }

  function syncAllTabs(lang) {
    // Sync all multi-lang containers to show the same language
    lang = canonicalLang(lang);
    var containers = document.querySelectorAll('.multi-lang[data-tabs-built]');
    for (var c = 0; c < containers.length; c++) {
      var container = containers[c];
      var tabs = container.querySelectorAll('.lang-tab');
      var found = false;
      var idx = 0;
      for (var t = 0; t < tabs.length; t++) {
        if (canonicalLang(tabs[t].getAttribute('data-lang')) === lang) {
          found = true;
          idx = t;
          break;
        }
      }
      if (!found) continue;
      activatePanel(container, idx);
    }
  }

  function restoreSavedLang() {
    try {
      var saved = localStorage.getItem('dl-lang');
      if (saved) syncAllTabs(saved);
    } catch(e) {}
  }

  function highlightAndRainbow() {
    if (typeof hljs === 'undefined') return;

    // Find unhighlighted code blocks
    var blocks = document.querySelectorAll('pre code:not(.hljs)');
    for (var i = 0; i < blocks.length; i++) {
      hljs.highlightElement(blocks[i]);
    }

    // Apply rainbow parens to Clojure blocks that haven't been processed
    var cljBlocks = document.querySelectorAll('code.hljs:not([data-rainbow])');
    for (var i = 0; i < cljBlocks.length; i++) {
      var el = cljBlocks[i];
      if (isClojureBlock(el)) {
        fixClojurePredicateSymbols(el);
        rainbowParens(el);
        el.setAttribute('data-rainbow', 'true');
      }
    }

    // Build multi-lang tabs after highlighting
    buildMultiLangTabs();
    restoreSavedLang();
  }

  // Run on initial page load
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', highlightAndRainbow);
  } else {
    highlightAndRainbow();
  }

  // Re-run after htmx swaps (for hx-boost navigation)
  document.addEventListener('htmx:afterSettle', highlightAndRainbow);
})();
