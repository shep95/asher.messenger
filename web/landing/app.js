/* Asher landing — page behaviour. No framework; the page is complete without JavaScript. */
(function () {
  'use strict';

  var reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');

  /* Masthead: hairline once scrolled */
  var masthead = document.querySelector('.masthead');
  var ticking = false;
  function onScroll() {
    if (ticking) return;
    ticking = true;
    window.requestAnimationFrame(function () {
      masthead.classList.toggle('is-scrolled', (window.scrollY || 0) > 12);
      ticking = false;
    });
  }
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  /* Mobile sheet */
  var toggle = document.querySelector('.masthead-toggle');
  var sheet = document.getElementById('sheet');
  function setSheet(open) {
    toggle.setAttribute('aria-expanded', String(open));
    toggle.setAttribute('aria-label', open ? 'Close menu' : 'Open menu');
    sheet.hidden = !open;
    document.body.style.overflow = open ? 'hidden' : '';
  }
  if (toggle && sheet) {
    toggle.addEventListener('click', function () { setSheet(toggle.getAttribute('aria-expanded') !== 'true'); });
    sheet.addEventListener('click', function (e) { if (e.target.closest('a')) setSheet(false); });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && !sheet.hidden) { setSheet(false); toggle.focus(); } });
  }

  /* Chapter reveal: one fade per section, once */
  var sections = [].slice.call(document.querySelectorAll('.hero, .chapter'));
  if ('IntersectionObserver' in window && !reduceMotion.matches) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) { en.target.classList.add('is-in'); io.unobserve(en.target); }
      });
    }, { rootMargin: '0px 0px -12% 0px', threshold: 0.05 });
    sections.forEach(function (s) { io.observe(s); });
  } else {
    sections.forEach(function (s) { s.classList.add('is-in'); });
  }

  /* Connection-state tabs (WAI-ARIA tabs pattern) */
  var tablist = document.querySelector('.scenes-tabs');
  if (tablist) {
    var tabs = [].slice.call(tablist.querySelectorAll('[role="tab"]'));
    var pill = document.getElementById('scene-pill');
    var pillDot = pill.querySelector('.scene-dot');
    var pillLabel = pill.querySelector('.scene-label');
    var lastTick = document.getElementById('last-tick');
    var LABEL = { orbit: 'Orbit', mesh: 'Mesh · 2 hops', carrying: 'Carrying', offline: 'Out of range' };
    var TICK = { orbit: 'read', mesh: 'delivered', carrying: 'carrying', offline: 'sending' };

    function select(tab, focus) {
      tabs.forEach(function (t) {
        var on = t === tab;
        t.setAttribute('aria-selected', String(on));
        t.tabIndex = on ? 0 : -1;
        document.getElementById(t.getAttribute('aria-controls')).hidden = !on;
      });
      var state = tab.getAttribute('data-state');
      pillDot.setAttribute('data-state', state);
      pillLabel.textContent = LABEL[state];
      lastTick.setAttribute('data-status', TICK[state]);
      if (focus) tab.focus();
      document.dispatchEvent(new CustomEvent('asher:scene', { detail: { state: state } }));
    }
    tabs.forEach(function (t, i) {
      t.addEventListener('click', function () { select(t, false); });
      t.addEventListener('keydown', function (e) {
        var j = null;
        if (e.key === 'ArrowRight') j = (i + 1) % tabs.length;
        if (e.key === 'ArrowLeft') j = (i - 1 + tabs.length) % tabs.length;
        if (e.key === 'Home') j = 0;
        if (e.key === 'End') j = tabs.length - 1;
        if (j !== null) { e.preventDefault(); select(tabs[j], true); }
      });
    });

    /* Idle demo: step through the states until the visitor touches the tabs. */
    var auto = null, idx = 0;
    function stopAuto() { if (auto) { clearInterval(auto); auto = null; } }
    tablist.addEventListener('pointerdown', stopAuto, { once: true });
    tablist.addEventListener('keydown', stopAuto, { once: true });
    if (!reduceMotion.matches && 'IntersectionObserver' in window) {
      var seen = new IntersectionObserver(function (entries) {
        entries.forEach(function (en) {
          if (en.isIntersecting && !auto) {
            auto = setInterval(function () { idx = (idx + 1) % tabs.length; select(tabs[idx], false); }, 3600);
          } else if (!en.isIntersecting) { stopAuto(); auto = null; }
        });
      }, { threshold: 0.4 });
      seen.observe(tablist.parentElement);
    }
  }
})();
