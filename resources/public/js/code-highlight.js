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

  function getLang(codeEl) {
    var cls = codeEl.className || '';
    var m = cls.match(/language-(\w+)/);
    return m ? m[1] : '';
  }

  function buildMultiLangTabs() {
    var containers = document.querySelectorAll('.multi-lang:not([data-tabs-built])');
    for (var c = 0; c < containers.length; c++) {
      var container = containers[c];
      var pres = container.querySelectorAll('pre');
      if (pres.length < 2) continue;

      // Build tab bar
      var tabBar = document.createElement('div');
      tabBar.className = 'lang-tabs';

      var panels = [];
      for (var p = 0; p < pres.length; p++) {
        var pre = pres[p];
        var code = pre.querySelector('code');
        var lang = code ? getLang(code) : '';
        var label = LANG_LABELS[lang] || lang || ('Tab ' + (p + 1));

        // Create tab button
        var tab = document.createElement('button');
        tab.className = 'lang-tab' + (p === 0 ? ' active' : '');
        tab.textContent = label;
        tab.setAttribute('data-lang', lang);
        tab.setAttribute('data-index', p);
        tabBar.appendChild(tab);

        // Wrap pre in a panel div
        var panel = document.createElement('div');
        panel.className = 'lang-panel' + (p === 0 ? ' active' : '');
        panel.setAttribute('data-lang', lang);
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
        var tabs = parent.querySelectorAll('.lang-tab');
        var pnls = parent.querySelectorAll('.lang-panel');
        for (var t = 0; t < tabs.length; t++) {
          tabs[t].classList.toggle('active', t === idx);
        }
        for (var q = 0; q < pnls.length; q++) {
          pnls[q].classList.toggle('active', q === idx);
        }

        // Remember selected language globally
        var lang = btn.getAttribute('data-lang');
        if (lang) {
          try { localStorage.setItem('dl-lang', lang); } catch(e) {}
          syncAllTabs(lang);
        }
      });

      container.setAttribute('data-tabs-built', 'true');
    }
  }

  function syncAllTabs(lang) {
    // Sync all multi-lang containers to show the same language
    var containers = document.querySelectorAll('.multi-lang[data-tabs-built]');
    for (var c = 0; c < containers.length; c++) {
      var container = containers[c];
      var tabs = container.querySelectorAll('.lang-tab');
      var panels = container.querySelectorAll('.lang-panel');
      var found = false;
      for (var t = 0; t < tabs.length; t++) {
        if (tabs[t].getAttribute('data-lang') === lang) {
          found = true;
          break;
        }
      }
      if (!found) continue;
      for (var t = 0; t < tabs.length; t++) {
        var isMatch = tabs[t].getAttribute('data-lang') === lang;
        tabs[t].classList.toggle('active', isMatch);
      }
      for (var p = 0; p < panels.length; p++) {
        var isMatch = panels[p].getAttribute('data-lang') === lang;
        panels[p].classList.toggle('active', isMatch);
      }
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
      var lang = el.className || '';
      if (lang.indexOf('clojure') !== -1 || lang.indexOf('clj') !== -1) {
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
