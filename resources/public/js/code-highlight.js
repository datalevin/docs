// Highlight.js init + rainbow parentheses for Clojure
(function() {
  var PAREN_COLORS = [
    '#e6c07b', // gold
    '#c678dd', // purple
    '#56b6c2', // cyan
    '#61afef', // blue
    '#e06c75', // red
    '#98c379', // green
  ];

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
