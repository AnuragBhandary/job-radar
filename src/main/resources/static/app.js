/*
 * Behaviour shared by every page.
 *
 * Three small things, each of which exists because of something that actually
 * went wrong, and none of which the pages need in order to work. Every form here
 * posts and redirects on its own; this only makes the waiting legible.
 *
 * Plain DOM. There is no build step in this project, no Node on the machine, and
 * nothing here is worth either.
 */
(function () {
  'use strict';

  /*
   * A submit button that has been pressed says so.
   *
   * Preparing an application takes the better part of a minute of browser
   * automation. A button that looks untouched for that long reads as a dead page
   * and gets pressed again, which used to start a second browser.
   */
  function markPending() {
    document.addEventListener('submit', function (event) {
      var button = event.target.querySelector('button[data-busy]');
      if (!button) { return; }
      button.setAttribute('data-pending', '1');
      button.setAttribute('aria-busy', 'true');
      // Left enabled: disabling a submit button inside its own submit handler
      // cancels the submission in some browsers, which is a worse bug than a
      // double click.
    }, true);
  }

  /* Suggestion chips that fill the box they belong to. */
  function fillFromChips() {
    document.addEventListener('click', function (event) {
      var chip = event.target.closest('[data-fill]');
      if (!chip) { return; }
      var box = document.getElementById(chip.getAttribute('data-fill'));
      if (!box) { return; }
      box.value = chip.getAttribute('data-value');
      box.focus();
    });
  }

  /*
   * Toasts leave.
   *
   * A confirmation that stays is clutter on every later glance at the page, and
   * after three of them it is not clear which action any of them refers to.
   */
  function fadeToasts() {
    document.querySelectorAll('.toast').forEach(function (toast) {
      window.setTimeout(function () {
        toast.style.transition = 'opacity 400ms';
        toast.style.opacity = '0';
        window.setTimeout(function () { toast.remove(); }, 400);
      }, 6000);
    });
  }

  markPending();
  fillFromChips();
  fadeToasts();
})();
