/*
 * Скрипт внедряется в каждую страницу m.youtube.com.
 *  1) Страница всегда «видна» → YouTube не ставит паузу при сворачивании.
 *  2) Автоподтверждение «Видео приостановлено. Продолжить?» + обновление _lact.
 *  3) Автопродолжение, если система/сайт остановили видео в фоне.
 *  4) Передача названия/автора/обложки/статуса в Android (YTBridge).
 *  5) Команды из шторки: play / pause / next / prev / seek.
 *  6) Свои вкладки: Главная, Шортс, Подписки, История (родная панель скрыта).
 */
(function () {
  if (window.__ytbgInstalled) return;
  window.__ytbgInstalled = true;

  /* ---------- 1. Страница всегда видима ---------- */
  function fake(obj, key, val) {
    try { Object.defineProperty(obj, key, { get: function () { return val; }, configurable: true }); } catch (e) {}
  }
  fake(Document.prototype, 'hidden', false);
  fake(Document.prototype, 'webkitHidden', false);
  fake(Document.prototype, 'visibilityState', 'visible');
  fake(Document.prototype, 'webkitVisibilityState', 'visible');
  try { Document.prototype.hasFocus = function () { return true; }; } catch (e) {}
  ['visibilitychange', 'webkitvisibilitychange', 'freeze'].forEach(function (t) {
    window.addEventListener(t, function (e) { e.stopImmediatePropagation(); }, true);
    document.addEventListener(t, function (e) { e.stopImmediatePropagation(); }, true);
  });
  window.addEventListener('blur', function (e) { if (e.target === window) e.stopImmediatePropagation(); }, true);

  /* ---------- Стили: скрываем родную нижнюю панель и рекламу «открыть в приложении» ---------- */
  var CSS =
    'html,body{background:#0f0f0f!important}' +
    'ytm-pivot-bar-renderer{display:none!important}' +
    'ytm-mealbar-promo-renderer,.mealbar-promo-renderer,ytm-upsell-dialog-renderer,' +
    'ytm-statement-banner-renderer,ytm-open-app-promo-renderer{display:none!important}';
  function addStyle() {
    if (document.getElementById('__ytbg_css')) return;
    var root = document.head || document.documentElement;
    if (!root) return;
    var s = document.createElement('style');
    s.id = '__ytbg_css';
    s.textContent = CSS;
    root.appendChild(s);
  }
  addStyle();

  var S = window.__ytbg = {
    bg: false, want: true, bgChangedAt: 0,
    lastKey: '', lastSentAt: 0, endedAt: 0
  };

  /* ---------- Утилиты ---------- */
  function isShorts() { return /^\/shorts/.test(location.pathname); }
  function player() { return document.getElementById('movie_player'); }

  function vid() {
    var vs = document.querySelectorAll('video'), withSrc = null;
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i];
      if (!v.paused && !v.ended) return v;
      if (!withSrc && (v.currentSrc || v.src)) withSrc = v;
    }
    return withSrc || vs[0] || null;
  }

  function pData() {
    try { var p = player(); if (p && p.getVideoData) return p.getVideoData() || {}; } catch (e) {}
    return {};
  }

  function urlId() {
    var m = location.pathname.match(/^\/shorts\/([\w-]{6,})/);
    if (m) return m[1];
    try { return new URLSearchParams(location.search).get('v') || ''; } catch (e) { return ''; }
  }

  function curVideoId() {
    if (isShorts()) return urlId();
    return pData().video_id || urlId();
  }

  function txt(sel) {
    var el = document.querySelector(sel);
    return el ? (el.textContent || '').trim() : '';
  }

  function meta() {
    var pd = isShorts() ? {} : pData();
    var id = curVideoId();
    var t = pd.title || '', a = pd.author || '', art = '';
    try {
      var md = navigator.mediaSession && navigator.mediaSession.metadata;
      if (md) {
        if (!t) t = md.title || '';
        if (!a) a = md.artist || '';
        if (md.artwork && md.artwork.length) art = md.artwork[md.artwork.length - 1].src || '';
      }
    } catch (e) {}
    if (!t) t = txt('.slim-video-information-title, .slim-video-metadata-title, .reel-player-header-title') ||
      (document.title || '').replace(/\s*-\s*YouTube\s*$/, '');
    if (!a) a = txt('.slim-owner-channel-name, ytm-slim-owner-renderer .slim-owner-channel-name, .reel-channel-name, .reel-player-header-channel-title');
    if (id) art = 'https://i.ytimg.com/vi/' + id + '/hqdefault.jpg';
    return { id: id || '', title: t, artist: a, art: art };
  }

  /* ---------- 4. Отчёт о состоянии в Android ---------- */
  function report(force) {
    var v = vid(), m = meta();
    var st = {
      id: m.id, title: m.title, artist: m.artist, art: m.art,
      playing: !!(v && !v.paused && !v.ended),
      pos: v ? Math.floor((v.currentTime || 0) * 1000) : 0,
      dur: (v && isFinite(v.duration)) ? Math.floor(v.duration * 1000) : 0,
      path: location.pathname,
      has: !!(v && m.id)
    };
    var key = [st.id, st.title, st.artist, st.art, st.playing, st.path, st.has, Math.round(st.dur / 1000)].join('|');
    var now = Date.now();
    if (force || key !== S.lastKey || now - S.lastSentAt > 5000) {
      S.lastKey = key; S.lastSentAt = now;
      try { window.YTBridge && YTBridge.onState(JSON.stringify(st)); } catch (e) {}
    }
  }

  /* ---------- 2. Защита от «Продолжить просмотр?» ---------- */
  var PAUSE_RX = /continue watching|still watching|video paused|продолжить просмотр|видео приостановлено|вы ещё смотрите|вы еще смотрите|davom ettir|tomosha/i;
  function keepAlive() {
    try { window._lact = Date.now(); } catch (e) {}
    try {
      var ds = document.querySelectorAll('ytm-dialog, [role="dialog"], tp-yt-paper-dialog, yt-confirm-dialog-renderer, ytm-confirm-dialog-renderer, .dialog-container');
      for (var i = 0; i < ds.length; i++) {
        if (PAUSE_RX.test(ds[i].innerText || '')) {
          var bs = ds[i].querySelectorAll('button, [role="button"], tp-yt-paper-button');
          if (bs.length) bs[bs.length - 1].click();
        }
      }
    } catch (e) {}
  }

  /* ---------- 3. Автопродолжение и автопереход ---------- */
  function guard() {
    var v = vid();
    if (!v) return;
    if (S.want && S.bg && v.paused && !v.ended && v.currentTime > 0) {
      var r = v.play(); if (r && r.catch) r.catch(function () {});
    }
    if (v.ended && S.want && !isShorts()) {
      if (!S.endedAt) S.endedAt = Date.now();
      else if (Date.now() - S.endedAt > 4000) { S.endedAt = 0; S.next(); }
    } else {
      S.endedAt = 0;
    }
  }

  document.addEventListener('play', function () { S.want = true; report(true); }, true);
  document.addEventListener('pause', function () {
    var t = Date.now();
    // Если в течение 1.5 с приложение не ушло в фон — паузу поставил сам пользователь
    setTimeout(function () { if (!S.bg && S.bgChangedAt < t) S.want = false; }, 1500);
    report(true);
  }, true);
  ['ended', 'loadedmetadata', 'durationchange'].forEach(function (t) {
    document.addEventListener(t, function () { report(true); }, true);
  });

  /* ---------- 5. Команды ---------- */
  function clickNextBtn() {
    var b = document.querySelector('.player-controls-next-button button, button.player-control-next-button, .ytp-next-button, ' +
      'button[aria-label^="Next"], button[aria-label^="Следующ"], button[aria-label^="Keyingi"]');
    if (b && !b.disabled) { b.click(); return true; }
    return false;
  }

  function openFirstRelated(exceptId) {
    var as = document.querySelectorAll('a[href*="/watch?v="]');
    for (var i = 0; i < as.length; i++) {
      var a = as[i], id = '';
      try { id = new URL(a.href, location.href).searchParams.get('v'); } catch (e) {}
      if (id && id !== exceptId && !a.closest('#player, #player-container-id, .player-container')) { a.click(); return true; }
    }
    return false;
  }

  function scrollShort(dir) {
    var el = vid();
    while (el && el !== document.body) {
      var cs = getComputedStyle(el);
      if ((cs.overflowY === 'scroll' || cs.overflowY === 'auto') && el.scrollHeight > el.clientHeight + 10) {
        el.scrollBy({ top: dir * el.clientHeight, behavior: 'smooth' });
        return;
      }
      el = el.parentElement;
    }
    var se = document.scrollingElement || document.documentElement;
    se.scrollBy({ top: dir * window.innerHeight, behavior: 'smooth' });
  }

  S.setBackground = function (b) { S.bg = !!b; S.bgChangedAt = Date.now(); };

  S.play = function () {
    S.want = true;
    try { var p = player(); if (p && p.playVideo && !isShorts()) p.playVideo(); } catch (e) {}
    var v = vid();
    if (v && v.paused) { var r = v.play(); if (r && r.catch) r.catch(function () {}); }
    setTimeout(function () { report(true); }, 300);
  };

  S.pause = function () {
    S.want = false;
    try { var p = player(); if (p && p.pauseVideo && !isShorts()) p.pauseVideo(); } catch (e) {}
    var v = vid(); if (v) v.pause();
    setTimeout(function () { report(true); }, 300);
  };

  S.toggle = function () { var v = vid(); if (v && !v.paused) S.pause(); else S.play(); };

  S.seek = function (ms) { var v = vid(); if (v) v.currentTime = ms / 1000; report(true); };

  S.next = function () {
    S.want = true;
    if (isShorts()) { scrollShort(1); return; }
    var before = curVideoId();
    try { var p = player(); if (p && p.nextVideo) p.nextVideo(); } catch (e) {}
    setTimeout(function () {
      if (curVideoId() === before) { if (!clickNextBtn()) openFirstRelated(before); }
    }, 1200);
  };

  S.prev = function () {
    S.want = true;
    if (isShorts()) { scrollShort(-1); return; }
    var v = vid();
    if (v && v.currentTime > 5) { v.currentTime = 0; return; }
    var before = curVideoId();
    try { var p = player(); if (p && p.previousVideo) p.previousVideo(); } catch (e) {}
    setTimeout(function () { if (curVideoId() === before) history.back(); }, 1000);
  };

  /* ---------- 6. Навигация по вкладкам (без перезагрузки, чтобы музыка не прерывалась) ---------- */
  var TABS = {
    home:    { pivot: '.pivot-w2w, .pivot-home', path: '/' },
    shorts:  { pivot: '.pivot-shorts', path: '/shorts' },
    subs:    { pivot: '.pivot-subs, .pivot-subscriptions', path: '/feed/subscriptions' },
    history: { pivot: null, path: '/feed/history' }
  };

  function clickEl(sel) {
    var el = document.querySelector(sel);
    if (!el) return false;
    (el.closest('ytm-pivot-bar-item-renderer') || el).click();
    return true;
  }

  function tryTab(tab) {
    var t = TABS[tab];
    if (tab === 'history') {
      if (clickEl('a[href^="/feed/history"]')) return true;
      if (clickEl('.pivot-library, .pivot-you')) {
        var n = 0, iv = setInterval(function () {
          n++;
          if (clickEl('a[href^="/feed/history"]')) clearInterval(iv);
          else if (n > 20) { clearInterval(iv); location.href = t.path; }
        }, 200);
        return true;
      }
      return false;
    }
    return t.pivot ? clickEl(t.pivot) : false;
  }

  S.nav = function (tab) {
    var t = TABS[tab];
    if (!t) return;
    if (location.pathname === t.path || (tab === 'shorts' && isShorts())) {
      window.scrollTo({ top: 0, behavior: 'smooth' });
      return;
    }
    if (tryTab(tab)) return;
    // На странице видео нижней панели нет — сначала через логотип на главную (без перезагрузки)
    var logo = document.querySelector('ytm-home-logo a, ytm-home-logo, a.mobile-topbar-header-endpoint');
    if (logo) {
      logo.click();
      if (tab === 'home') return;
      var n = 0, iv = setInterval(function () {
        n++;
        if (tryTab(tab)) clearInterval(iv);
        else if (n > 15) { clearInterval(iv); location.href = t.path; }
      }, 200);
      return;
    }
    location.href = t.path;
  };

  /* ---------- Главный цикл ---------- */
  setInterval(function () {
    addStyle();
    keepAlive();
    guard();
    report(false);
  }, 1000);
  document.addEventListener('DOMContentLoaded', function () { addStyle(); report(true); });
})();
