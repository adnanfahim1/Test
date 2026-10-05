/* =========================================================
   Codexa landing — reference interactions (vanilla JS, no deps)
   1. Hero parallax (orbit layer leans toward the mouse)
   2. Coming-soon: scroll-driven glass panes + mouse tilt + hover
   3. Codexa "O" progress loader (appears once panes have moved away)
   4. Magnetic "Learn more" button
   ========================================================= */
(function () {
  'use strict';

  var reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  // Overall strength of the glass motion (0 = off, 2 = strong). Set in Settings → Codexa Landing,
  // printed as data-cdx-liquid on .cdx-page by the plugin; falls back to 1.
  var pageEl = document.querySelector('.cdx-page[data-cdx-liquid]');
  var LIQUID = pageEl ? parseFloat(pageEl.getAttribute('data-cdx-liquid')) : 1;
  if (!isFinite(LIQUID)) LIQUID = 1;
  LIQUID = Math.max(0, Math.min(2, LIQUID));
  var LOADER_AT = 0.82;      // scroll progress at which the loader appears

  function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
  function ease(t) { return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2; }

  /* ---------- 1. Hero parallax ---------- */
  var hero = document.querySelector('[data-cdx-hero]');
  var heroLayer = document.querySelector('[data-cdx-hero-parallax]');
  if (hero && heroLayer && !reduceMotion) {
    hero.addEventListener('mousemove', function (e) {
      var r = hero.getBoundingClientRect();
      var hx = (e.clientX - r.left) / r.width * 2 - 1;
      var hy = (e.clientY - r.top) / r.height * 2 - 1;
      heroLayer.style.transform = 'translate3d(' + (hx * 18).toFixed(1) + 'px,' + (hy * 14).toFixed(1) + 'px,0)';
    });
    hero.addEventListener('mouseleave', function () { heroLayer.style.transform = ''; });
  }

  /* ---------- 4. Magnetic button ---------- */
  var magnet = document.querySelector('[data-cdx-magnet]');
  var magnetTarget = document.querySelector('[data-cdx-magnet-target]');
  if (magnet && magnetTarget && !reduceMotion) {
    magnet.addEventListener('mousemove', function (e) {
      var r = magnet.getBoundingClientRect();
      var nx = clamp((e.clientX - (r.left + r.width / 2)) / 160, -1, 1);
      var ny = clamp((e.clientY - (r.top + r.height / 2)) / 50, -1, 1);
      magnetTarget.style.transform = 'translate(' + (nx * 10).toFixed(1) + 'px,' + (ny * 8).toFixed(1) + 'px)';
    });
    magnet.addEventListener('mouseleave', function () { magnetTarget.style.transform = ''; });
  }

  /* ---------- 2 + 3. Coming soon ---------- */
  var soon = document.querySelector('[data-cdx-soon]');
  if (!soon) return;
  var box = soon.querySelector('[data-cdx-soon-box]');
  var title = soon.querySelector('[data-cdx-soon-title]');
  var loader = soon.querySelector('[data-cdx-loader]');
  var ring = soon.querySelector('[data-cdx-loader-ring]');
  var bar = soon.querySelector('[data-cdx-loader-bar]');
  var paneEls = Array.prototype.slice.call(soon.querySelectorAll('[data-cdx-pane]'));

  // Base geometry (must match the inline left/top/width/height in the HTML)
  var BASE = [
    { left: 60,  top: 120, w: 460, h: 460, rot: -38, depth: 1.4, travel: 220, spin: 18,  a: 0.22 },
    { left: 420, top: 200, w: 540, h: 420, rot: -14, depth: 1.0, travel: 140, spin: -12, a: 0.24 },
    { left: 180, top: 420, w: 440, h: 440, rot: -30, depth: 0.7, travel: 260, spin: 22,  a: 0.14 }
  ];

  var S = { prog: 0, W: 1280, mx: 0, my: 0, px: 50, py: 50, hover: -1 };

  // Small screens (same breakpoint as the CSS): the loader sits centred at the bottom and the title
  // fills the width, so the panes rest smaller, in the band between the title and the loader.
  var mobileMq = window.matchMedia ? window.matchMedia('(max-width: 760px)') : null;

  function targets(W) {
    if (mobileMq && mobileMq.matches) {
      var f = clamp(W / 375, 1, 1.8);
      return [
        { cx: W - 80 * f,  cy: 430, sc: 0.28 * f, rot: -16, arc: -50 },
        { cx: 88 * f,      cy: 405, sc: 0.22 * f, rot: -6,  arc: -40 },
        { cx: W * 0.5,     cy: 490, sc: 0.2 * f,  rot: -30, arc: 40 }
      ];
    }
    return [
      { cx: W - 230,      cy: 175, sc: 0.42, rot: -16, arc: -70 },
      { cx: W * 0.5 + 60, cy: 120, sc: 0.35, rot: -6,  arc: -50 },
      { cx: 230,          cy: 790, sc: 0.45, rot: -30, arc: 60 }
    ];
  }

  function render() {
    var k = reduceMotion ? 0 : LIQUID;
    var c = S.prog - 0.5;
    var tg = targets(S.W);

    paneEls.forEach(function (el, i) {
      var b = BASE[i], t = tg[i], hov = S.hover === i;
      var p = clamp((S.prog - 0.3 - i * 0.07) / 0.36, 0, 1);
      var e = ease(p);
      var dx = t.cx - (b.left + b.w / 2);
      var dy = t.cy - (b.top + b.h / 2);
      var arc = Math.sin(Math.PI * e) * t.arc;
      var par = 1 - e * 0.6;
      var tx = dx * e + arc * 0.5 + S.mx * 26 * b.depth * k * par;
      var ty = dy * e + arc + (-c * b.travel * (1 - e) + S.my * 18 * b.depth) * k * par;
      var rot = b.rot + (t.rot - b.rot) * e + Math.sin(Math.PI * e) * b.spin * k + S.mx * 4 * k * par;
      var sc = (1 + (t.sc - 1) * e) * (hov ? 1.08 : 1);
      el.style.transform = 'translate3d(' + tx.toFixed(1) + 'px,' + ty.toFixed(1) + 'px,0) rotate(' + rot.toFixed(2) + 'deg) scale(' + sc.toFixed(3) + ')';

      var g = el.querySelector('.cdx-glass');
      g.style.setProperty('--blur', (hov ? 26 : 14 + i * 2) + 'px');
      g.style.setProperty('--a', String(hov ? b.a + 0.1 : b.a));
      g.style.setProperty('--edge', hov ? 'rgba(255,255,255,0.65)' : 'rgba(255,255,255,0.3)');
      g.style.setProperty('--hx', S.px + '%');
      g.style.setProperty('--hy', S.py + '%');
      g.style.setProperty('--shine', hov ? '0.45' : '0.18');
    });

    soon.style.setProperty('--gx', (88 - S.mx * 8 * k).toFixed(1) + '%');
    soon.style.setProperty('--gy', (6 + c * 20 * k).toFixed(1) + '%');
    if (title) title.style.transform = 'translate3d(0,' + (c * 40 * k).toFixed(1) + 'px,0)';

    setLoaderVisible(S.prog >= LOADER_AT);
  }

  /* Scroll progress: 0 when the section's top enters the viewport bottom, 1 at the page end. */
  var rafId = 0;
  function measure() {
    rafId = 0;
    var r = soon.getBoundingClientRect();
    var vh = window.innerHeight || 900;
    S.prog = clamp((vh - r.top) / r.height, 0, 1);
    S.W = box ? box.clientWidth : 1280;
    render();
  }
  function schedule() { if (!rafId) rafId = requestAnimationFrame(measure); }
  window.addEventListener('scroll', schedule, { passive: true });
  window.addEventListener('resize', schedule);

  if (!reduceMotion) {
    soon.addEventListener('mousemove', function (e) {
      var r = soon.getBoundingClientRect();
      var fx = (e.clientX - r.left) / r.width, fy = (e.clientY - r.top) / r.height;
      S.mx = fx * 2 - 1; S.my = fy * 2 - 1; S.px = Math.round(fx * 100); S.py = Math.round(fy * 100);
      schedule();
    });
    soon.addEventListener('mouseleave', function () { S.mx = 0; S.my = 0; S.px = 50; S.py = 50; S.hover = -1; schedule(); });
    paneEls.forEach(function (el, i) {
      el.addEventListener('mouseenter', function () { S.hover = i; schedule(); });
      el.addEventListener('mouseleave', function () { S.hover = -1; schedule(); });
    });
  }

  /* ---------- 3. Loader ---------- */
  var loaderOn = false, loaderRaf = 0, t0 = 0;
  var CYCLE = 5400, FILL = 4200;   // ms: 4.2s fill, ~1.2s hold, then restart
  function loaderFrame(now) {
    var el = (now - t0) % CYCLE;
    var load = ease(Math.min(1, el / FILL));
    ring.style.strokeDashoffset = (318 * (1 - Math.min(1, load / 0.92))).toFixed(1);
    bar.style.transform = 'scaleX(' + clamp((load - 0.92) / 0.08, 0, 1).toFixed(2) + ')';
    loaderRaf = requestAnimationFrame(loaderFrame);
  }
  function setLoaderVisible(on) {
    if (!loader || on === loaderOn) return;
    loaderOn = on;
    loader.classList.toggle('is-visible', on);
    if (reduceMotion) return; // CSS shows the completed mark
    if (on) { t0 = performance.now(); loaderRaf = requestAnimationFrame(loaderFrame); }
    else {
      cancelAnimationFrame(loaderRaf); loaderRaf = 0;
      ring.style.strokeDashoffset = '318'; bar.style.transform = 'scaleX(0)';
    }
  }

  measure();
})();
