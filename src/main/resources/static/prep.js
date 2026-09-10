/*
 * The Application Preparation screen's behaviour.
 *
 * Four independent pieces, each initialised on its own and each optional: every
 * action on the screen is a real form that posts and redirects. This adds only
 * the things a form cannot do - watching a browser work in the background, and
 * opening a panel without losing the page.
 *
 * Written as separate functions rather than one long block, so a change to the
 * explanation panel cannot break the polling. They share nothing but `escape`.
 *
 * Plain DOM and plain fetch. There is no build step in this project, no Node on
 * the machine, and nothing here is worth either.
 */
(function () {
  'use strict';

  /*
   * Everything written into the page goes through here.
   *
   * The values are field labels and answers that came off a third-party job
   * board, and they are being written into a page with buttons that submit real
   * applications.
   */
  function escape(value) {
    return String(value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  /* ---------------------------------------------------------------
   * 1. Watching a preparation run.
   *
   * Polls while the server says something is running, and reloads once it stops
   * - rather than rebuilding the page from JSON, which would mean two renderers
   * for one screen and only one of them ever exercised.
   * ------------------------------------------------------------- */
  function watchPreparation() {
    var panel = document.getElementById('prep');
    if (!panel || panel.getAttribute('data-running') !== 'true') { return; }

    var attemptId = panel.getAttribute('data-attempt');
    var stageLabel = document.getElementById('prep-stage');
    var stageDetail = document.getElementById('prep-detail');
    var misses = 0;

    function tick() {
      fetch('/api/preparation/' + attemptId, { headers: { Accept: 'application/json' } })
        .then(function (response) {
          if (!response.ok) { throw new Error('unavailable'); }
          return response.json();
        })
        .then(function (view) {
          misses = 0;
          if (!view.running) {
            window.location.reload();
            return;
          }
          if (stageLabel && view.stageLabel) { stageLabel.textContent = view.stageLabel; }
          if (stageDetail) { stageDetail.textContent = view.stageDetail || ''; }
          window.setTimeout(tick, 1500);
        })
        .catch(function () {
          // The server going quiet mid-run is worth saying out loud rather than
          // polling into silence: the process may have been restarted under it.
          misses += 1;
          if (misses > 8) {
            if (stageDetail) {
              stageDetail.textContent =
                'Lost contact with job-radar. Reload the page to see where this got to.';
            }
            return;
          }
          window.setTimeout(tick, 2000);
        });
    }
    window.setTimeout(tick, 1200);
  }

  /* ---------------------------------------------------------------
   * 2. "Why?"
   *
   * Fetched when it is opened rather than rendered with the page. The
   * explanation is the resolver's current answer, and a copy baked into the
   * HTML an hour ago would be the one thing on screen that could disagree with
   * the field it explains.
   * ------------------------------------------------------------- */
  function explanations() {
    document.addEventListener('click', function (event) {
      var button = event.target.closest('[data-why]');
      if (!button) { return; }
      var id = button.getAttribute('data-why');
      var box = document.getElementById('why-' + id);
      if (!box) { return; }

      if (!box.hidden) {
        box.hidden = true;
        button.setAttribute('aria-expanded', 'false');
        return;
      }
      box.hidden = false;
      button.setAttribute('aria-expanded', 'true');
      if (box.getAttribute('data-loaded') === '1') { return; }
      box.innerHTML = '<p class="caption">Working it out…</p>';

      fetch('/api/field/' + id + '/explain', { headers: { Accept: 'application/json' } })
        .then(function (response) { return response.json(); })
        .then(function (why) {
          box.innerHTML = renderWhy(why);
          box.setAttribute('data-loaded', '1');
        })
        .catch(function () {
          box.innerHTML = '<p class="caption">Could not load the explanation.</p>';
        });
    });
  }

  function renderWhy(why) {
    var out = '<dl class="why-grid">';
    out += row('Answer', why.answer || '(none)');
    out += row('Question', why.conceptLabel);
    out += row('Context', why.context);
    out += row('Source', why.sourceLabel || why.source);
    out += row('Confidence', why.confidence);
    if (why.failureReason) { out += row('Not filled because', why.failureReason); }
    if (why.userEdited && why.originalValue) {
      out += row('Before you changed it',
        why.originalValue + ' (' + why.originalSource + ')');
    }
    out += '</dl>';

    if (why.explanation) {
      out += '<p class="why-line">' + escape(why.explanation) + '</p>';
    }
    out += list('What this was based on', why.evidence);
    out += list('Also applied, and lost', why.alsoApplied);
    if (why.conflict && why.conflict.length) {
      out += '<p class="why-line why-conflict">These disagree, and nothing has chosen '
        + 'between them:</p>' + bare(why.conflict);
    }
    if (why.secondOpinion) {
      out += '<p class="caption">The new resolver would say: '
        + escape(why.secondOpinion) + ' — shown for comparison, not used.</p>';
    }
    return out;
  }

  function row(label, value) {
    if (!value) { return ''; }
    return '<dt>' + escape(label) + '</dt><dd>' + escape(value) + '</dd>';
  }

  function list(title, items) {
    if (!items || !items.length) { return ''; }
    return '<p class="why-sub">' + escape(title) + '</p>' + bare(items);
  }

  function bare(items) {
    var out = '<ul class="why-list">';
    for (var i = 0; i < items.length; i++) {
      out += '<li>' + escape(items[i]) + '</li>';
    }
    return out + '</ul>';
  }

  /* ---------------------------------------------------------------
   * 3. Editing a draft before approving it.
   * ------------------------------------------------------------- */
  function inlineEditors() {
    document.addEventListener('click', function (event) {
      var button = event.target.closest('[data-edit]');
      if (!button) { return; }
      var form = document.getElementById('edit-' + button.getAttribute('data-edit'));
      if (!form) { return; }
      form.hidden = !form.hidden;
      button.textContent = form.hidden ? 'Edit' : 'Cancel edit';
      button.setAttribute('aria-expanded', String(!form.hidden));
      if (!form.hidden) {
        var box = form.querySelector('textarea');
        if (box) { box.focus(); }
      }
    });
  }

  /* ---------------------------------------------------------------
   * 4. The screenshot.
   *
   * It is 1440 by 4536 pixels. Left to itself it is three thousand pixels of
   * page and everything below it is unreachable, which is how it behaved before
   * this frame existed.
   * ------------------------------------------------------------- */
  function screenshotFrame() {
    var toggle = document.getElementById('shot-toggle');
    var frame = document.getElementById('shot-frame');
    if (!toggle || !frame) { return; }
    toggle.addEventListener('click', function () {
      var open = frame.classList.toggle('is-expanded');
      toggle.setAttribute('aria-expanded', String(open));
      toggle.textContent = open ? 'Collapse' : 'Expand';
    });
  }

  watchPreparation();
  explanations();
  inlineEditors();
  screenshotFrame();
})();
