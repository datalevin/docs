(function() {
  function formatStars(n) {
    if (n >= 1000) return (n / 1000).toFixed(1).replace(/\.0$/, '') + 'k';
    return String(n);
  }

  function update() {
    var el = document.getElementById('gh-stars');
    if (!el) return;
    var cached = sessionStorage.getItem('gh-stars');
    if (cached) { el.textContent = cached; return; }
    fetch('https://api.github.com/repos/datalevin/datalevin')
      .then(function(r) { return r.json(); })
      .then(function(data) {
        if (data.stargazers_count) {
          var text = formatStars(data.stargazers_count) + ' \u2605';
          el.textContent = text;
          sessionStorage.setItem('gh-stars', text);
        }
      })
      .catch(function() {});
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', update);
  } else {
    update();
  }
  document.addEventListener('htmx:afterSettle', update);
})();
