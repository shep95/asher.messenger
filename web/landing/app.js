/* Asher landing — behaviour
   No framework. Everything here degrades to a static page without JavaScript. */
(function () {
  'use strict';

  var reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  var FAST = 160, BASE = 240, SLOW = 400; // motion tokens (ms)

  /* ---------- Nav ---------- */
  var nav = document.querySelector('.nav');
  var toggle = document.querySelector('.nav-toggle');
  var menu = document.getElementById('nav-menu');

  if (toggle && menu) {
    toggle.addEventListener('click', function () {
      var open = toggle.getAttribute('aria-expanded') === 'true';
      toggle.setAttribute('aria-expanded', String(!open));
      toggle.setAttribute('aria-label', open ? 'Open menu' : 'Close menu');
      menu.classList.toggle('is-open', !open);
    });
    menu.addEventListener('click', function (e) {
      if (e.target.closest('a')) {
        toggle.setAttribute('aria-expanded', 'false');
        toggle.setAttribute('aria-label', 'Open menu');
        menu.classList.remove('is-open');
      }
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && menu.classList.contains('is-open')) {
        toggle.setAttribute('aria-expanded', 'false');
        menu.classList.remove('is-open');
        toggle.focus();
      }
    });
  }

  /* ---------- Scroll: nav border + wallpaper parallax ---------- */
  var heroBg = document.querySelector('.hero-bg');
  var hero = document.querySelector('.hero');
  var ticking = false;

  function onScroll() {
    if (ticking) return;
    ticking = true;
    window.requestAnimationFrame(function () {
      var y = window.scrollY || window.pageYOffset;
      if (nav) nav.classList.toggle('is-scrolled', y > 8);
      if (heroBg && hero && !reduceMotion.matches) {
        var h = hero.offsetHeight;
        if (y < h) heroBg.style.transform = 'translate3d(0,' + Math.round(y * 0.22) + 'px,0)';
      }
      ticking = false;
    });
  }
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  reduceMotion.addEventListener && reduceMotion.addEventListener('change', function () {
    if (heroBg) heroBg.style.transform = '';
    restartDemo();
  });

  /* ---------- Scene indicator pill (hero) ---------- */
  var SCENES = [
    { state: 'orbit', label: 'Orbit' },
    { state: 'mesh', label: 'Mesh · 2 hops' },
    { state: 'carrying', label: 'Carrying' }
  ];

  function setScene(pill, state, label) {
    if (!pill) return;
    var dot = pill.querySelector('.scene-dot');
    var text = pill.querySelector('.scene-label');
    if (text.textContent === label) return;
    pill.classList.add('is-switching');
    window.setTimeout(function () {
      dot.setAttribute('data-state', state);
      text.textContent = label;
      pill.classList.remove('is-switching');
    }, FAST);
  }

  var heroScene = document.getElementById('hero-scene');
  if (heroScene) {
    var si = 0;
    window.setInterval(function () {
      si = (si + 1) % SCENES.length;
      setScene(heroScene, SCENES[si].state, SCENES[si].label);
    }, 3200);
  }

  /* ---------- Conversation demo ---------- */
  var thread = document.getElementById('thread');
  var demoScene = document.getElementById('demo-scene');
  var timers = [];
  var demoVisible = true;
  var demoRunning = false;

  var GLYPHS = {
    sending: '<svg class="glyph-sending" viewBox="0 0 14 14" aria-hidden="true"><circle cx="7" cy="7" r="4.5" fill="none" stroke="currentColor" stroke-width="1.3"/></svg>',
    sent: '<svg class="glyph-sent" viewBox="0 0 14 14" aria-hidden="true"><path d="M2.5 7.5l3 3 6-6.5" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    delivered: '<svg class="glyph-delivered" viewBox="0 0 14 14" aria-hidden="true"><path d="M1 7.5l3 3 5.5-6M6 10.5l1 1 6-6.5" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    read: '<svg class="glyph-read" viewBox="0 0 14 14" aria-hidden="true"><path d="M1 7.5l3 3 5.5-6M6 10.5l1 1 6-6.5" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    carrying: '<svg class="glyph-carrying" viewBox="0 0 14 14" aria-hidden="true"><path d="M11.6 4.6A5 5 0 1 1 7 2" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/><circle cx="11.8" cy="4.2" r="1.3" fill="currentColor"/></svg>'
  };

  var STATUS_LABEL = { sending: 'Sending', sent: 'Sent', delivered: 'Delivered', read: 'Read', carrying: 'Carrying, waiting for a relay' };

  function el(html) {
    var t = document.createElement('template');
    t.innerHTML = html.trim();
    return t.content.firstChild;
  }

  function esc(s) {
    return s.replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; });
  }

  function makeMessage(m, animate) {
    var same = m.same ? ' msg-same' : '';
    var enter = animate ? ' enter' : '';
    var html;
    if (m.dir === 'out') {
      html = '<div class="msg msg-out' + same + enter + '">' +
        '<div class="bubble">' + esc(m.text) +
        '<span class="meta"><time>' + m.time + '</time>' +
        '<span class="status" role="img" aria-label="' + STATUS_LABEL[m.status] + '">' +
        GLYPHS.sending + GLYPHS.sent + GLYPHS.delivered + GLYPHS.read + GLYPHS.carrying +
        '</span></span></div></div>';
    } else {
      html = '<div class="msg msg-in' + same + enter + '">' +
        '<span class="avatar avatar-28" aria-hidden="true">M</span>' +
        '<div class="bubble">' + esc(m.text) + '<span class="meta"><time>' + m.time + '</time></span></div></div>';
    }
    var node = el(html);
    if (m.dir === 'out') setStatus(node, m.status);
    return node;
  }

  function setStatus(node, status) {
    var box = node.querySelector('.status');
    if (!box) return;
    box.setAttribute('aria-label', STATUS_LABEL[status]);
    var svgs = box.querySelectorAll('svg');
    for (var i = 0; i < svgs.length; i++) {
      svgs[i].classList.toggle('is-on', svgs[i].classList.contains('glyph-' + status));
    }
  }

  function makeTyping() {
    return el('<div class="msg msg-in typing enter" aria-label="Mara is typing">' +
      '<span class="avatar avatar-28" aria-hidden="true">M</span>' +
      '<div class="bubble"><span class="dot"></span><span class="dot"></span><span class="dot"></span></div></div>');
  }

  // The script. Times are offsets in ms from the start of a loop.
  var SCRIPT = [
    { at: 0,     do: function (s) { s.scene('carrying', 'Carrying'); s.m1 = s.add({ dir: 'out', text: 'Made it to the ridge. No signal up here.', time: '09:40', status: 'carrying' }); } },
    { at: 1900,  do: function (s) { s.scene('mesh', 'Mesh · 2 hops'); setStatus(s.m1, 'delivered'); } },
    { at: 2500,  do: function (s) { s.typing = s.add(makeTyping()); } },
    { at: 4300,  do: function (s) { s.remove(s.typing); s.add({ dir: 'in', text: 'Got you through the LoRa node at the hut.', time: '09:43' }); } },
    { at: 5100,  do: function (s) { s.add({ dir: 'in', same: true, text: 'Stay put, we are coming up.', time: '09:43' }); } },
    { at: 6900,  do: function (s) { s.m3 = s.add({ dir: 'out', text: 'Copy. Light is going, I will keep the beacon on.', time: '09:44', status: 'sending' }); } },
    { at: 7400,  do: function (s) { setStatus(s.m3, 'sent'); } },
    { at: 8600,  do: function (s) { setStatus(s.m3, 'delivered'); } },
    { at: 10400, do: function (s) { setStatus(s.m3, 'read'); } },
    { at: 14000, do: function (s) { s.reset(); } }
  ];
  var LOOP_MS = 14000 + SLOW + 200;

  // Static end state, used under reduced motion and as a fallback.
  var STATIC = [
    { dir: 'out', text: 'Made it to the ridge. No signal up here.', time: '09:40', status: 'delivered' },
    { dir: 'in', text: 'Got you through the LoRa node at the hut.', time: '09:43' },
    { dir: 'in', same: true, text: 'Stay put, we are coming up.', time: '09:43' },
    { dir: 'out', text: 'Copy. Light is going, I will keep the beacon on.', time: '09:44', status: 'read' }
  ];

  function clearTimers() {
    while (timers.length) window.clearTimeout(timers.pop());
  }

  function clearThread() {
    if (!thread) return;
    thread.classList.remove('is-resetting');
    var kids = thread.querySelectorAll('.msg');
    for (var i = 0; i < kids.length; i++) kids[i].remove();
  }

  function renderStatic() {
    clearTimers();
    clearThread();
    for (var i = 0; i < STATIC.length; i++) thread.appendChild(makeMessage(STATIC[i], false));
    setScene(demoScene, 'mesh', 'Mesh · 2 hops');
    demoRunning = false;
  }

  function runLoop() {
    clearTimers();
    clearThread();
    demoRunning = true;
    var state = {
      add: function (m) {
        var node = m.nodeType ? m : makeMessage(m, true);
        thread.appendChild(node);
        window.setTimeout(function () { node.classList.remove('enter'); }, BASE + 20);
        return node;
      },
      remove: function (node) { if (node && node.parentNode) node.parentNode.removeChild(node); },
      scene: function (st, label) { setScene(demoScene, st, label); },
      reset: function () {
        thread.classList.add('is-resetting');
        timers.push(window.setTimeout(clearThread, SLOW + 20));
      }
    };
    SCRIPT.forEach(function (step) {
      timers.push(window.setTimeout(function () { step.do(state); }, step.at));
    });
    timers.push(window.setTimeout(function () { if (demoVisible && !document.hidden) runLoop(); else demoRunning = false; }, LOOP_MS));
  }

  function restartDemo() {
    if (!thread) return;
    if (reduceMotion.matches) renderStatic();
    else if (demoVisible && !document.hidden) runLoop();
  }

  if (thread) {
    // Pause the loop when the demo is off screen or the tab is hidden.
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (entries) {
        demoVisible = entries[0].isIntersecting;
        if (demoVisible && !demoRunning && !reduceMotion.matches) runLoop();
      }, { threshold: 0.2 }).observe(thread);
    }
    document.addEventListener('visibilitychange', function () {
      if (!document.hidden && !demoRunning && demoVisible && !reduceMotion.matches) runLoop();
    });
    restartDemo();
  }
})();
