(function() {
  function initFlashDismiss(root) {
    root.querySelectorAll('[data-flash-auto-dismiss="true"]:not([data-auto-dismiss-init])').forEach(function(el) {
      el.setAttribute('data-auto-dismiss-init', 'true');
      window.setTimeout(function() {
        el.remove();
      }, 3000);
    });
  }

  function openHashTargets(root) {
    if (!window.location.hash) {
      return;
    }

    root.querySelectorAll('[data-open-when-hash]').forEach(function(el) {
      if (el.getAttribute('data-open-when-hash') === window.location.hash) {
        el.classList.remove('hidden');
      }
    });
  }

  function init(root) {
    const scope = root && root.querySelectorAll ? root : document;
    initFlashDismiss(scope);
    openHashTargets(scope);
  }

  document.addEventListener('click', function(event) {
    const confirmable = event.target.closest('[data-confirm-message]');
    if (confirmable) {
      const message = confirmable.getAttribute('data-confirm-message');
      if (message && !window.confirm(message)) {
        event.preventDefault();
        event.stopPropagation();
        return;
      }
    }

    const toggle = event.target.closest('[data-toggle-target]');
    if (toggle) {
      const target = document.querySelector(toggle.getAttribute('data-toggle-target'));
      const className = toggle.getAttribute('data-toggle-class') || 'hidden';
      if (target) {
        target.classList.toggle(className);
      }
      event.preventDefault();
      return;
    }

    const hide = event.target.closest('[data-hide-target]');
    if (hide) {
      const target = document.querySelector(hide.getAttribute('data-hide-target'));
      const className = hide.getAttribute('data-hide-class') || 'hidden';
      if (target) {
        target.classList.add(className);
      }
      event.preventDefault();
      return;
    }

    const removeClosest = event.target.closest('[data-remove-closest]');
    if (removeClosest) {
      const target = removeClosest.closest(removeClosest.getAttribute('data-remove-closest'));
      if (target) {
        target.remove();
      }
      event.preventDefault();
    }
  });

  window.addEventListener('hashchange', function() {
    openHashTargets(document);
  });

  document.addEventListener('htmx:afterSwap', function(event) {
    init((event.detail && event.detail.elt) || document);
  });

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() {
      init(document);
    });
  } else {
    init(document);
  }
})();
