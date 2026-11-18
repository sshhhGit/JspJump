/*
 * JSP Jump bookmarklet - readable source.
 *
 * Click the bookmarklet -> jump mode ON (element under the mouse is highlighted).
 * Click once            -> clues are sent to IntelliJ (POST http://localhost:63342/jspjump), jump mode OFF.
 * Esc                   -> cancel. Clicking the bookmarklet again also cancels.
 *
 * Rules for this file (so build.js can turn it into a one-line javascript: URL):
 *   - block comments only (no line comments), every statement ends with a semicolon.
 */
(function () {
  'use strict';
  var TOP = window;
  var Z = 2147483647;

  if (TOP.__jspJump && TOP.__jspJump.active) {
    TOP.__jspJump.stop();
    TOP.__jspJump.toast('JSP Jump: 취소했습니다.', 'info', 1500);
    return;
  }

  /*
   * Which IDE? The built-in server port is 63342 by default, but the next free port (63343...) is used when
   * another JetBrains IDE already took it, and it can be changed in the IDE settings. Order of attempts:
   * port remembered for this site -> port that served this script -> 63342..63361 (only when needed).
   */
  var PORT_KEY = 'jspjump.port';
  var FIRST_PORT = 63342, LAST_PORT = 63361;
  var cs = document.currentScript;
  var fixedEndpoint = TOP.JSPJUMP_ENDPOINT || null;
  var servedPort = null;
  if (cs && cs.src) {
    var mp = /^https?:\/\/[^\/]+:(\d+)\/jspjump\/bookmarklet\.js/.exec(cs.src);
    if (mp) { servedPort = +mp[1]; }
  }
  function rememberedPort() {
    try { return +TOP.localStorage.getItem(PORT_KEY) || null; } catch (e) { return null; }
  }
  function rememberPort(port) {
    try { TOP.localStorage.setItem(PORT_KEY, String(port)); } catch (e) { /* storage blocked */ }
  }
  function endpointOf(port) { return 'http://localhost:' + port + '/jspjump'; }

  var state = TOP.__jspJump = TOP.__jspJump || {};
  state.active = true;

  /* ---------------------------------------------------------------- UI */

  function mk(tag, css, text) {
    var e = document.createElement(tag);
    e.setAttribute('data-jspjump', '1');
    e.style.cssText = css;
    if (text) { e.textContent = text; }
    return e;
  }

  var box = mk('div', 'position:fixed;pointer-events:none;z-index:' + Z + ';border:2px solid #2E6BD9;' +
    'background:rgba(46,107,217,.12);border-radius:2px;display:none;box-sizing:border-box;');
  var tip = mk('div', 'position:fixed;pointer-events:none;z-index:' + Z + ';background:#1B3F86;color:#fff;' +
    'font:12px/1.4 Consolas,monospace;padding:2px 6px;border-radius:3px;display:none;white-space:nowrap;' +
    'max-width:70vw;overflow:hidden;text-overflow:ellipsis;');
  var banner = mk('div', 'position:fixed;top:8px;left:50%;transform:translateX(-50%);z-index:' + Z + ';' +
    'background:#2E6BD9;color:#fff;font:bold 13px/1.4 sans-serif;padding:6px 14px;border-radius:16px;' +
    'box-shadow:0 2px 8px rgba(0,0,0,.3);pointer-events:none;',
    'JSP Jump: 소스로 이동할 요소를 클릭하세요 (Esc 취소)');
  document.body.appendChild(box);
  document.body.appendChild(tip);
  document.body.appendChild(banner);

  function toast(msg, kind, ms) {
    var old = document.getElementById('__jspjump_toast');
    if (old) { old.parentNode.removeChild(old); }
    var bg = kind === 'ok' ? '#2E7D32' : (kind === 'warn' ? '#B26A00' : (kind === 'error' ? '#C62828' : '#37474F'));
    var t = mk('div', 'position:fixed;right:16px;bottom:16px;z-index:' + Z + ';background:' + bg + ';color:#fff;' +
      'font:13px/1.5 sans-serif;padding:10px 14px;border-radius:6px;box-shadow:0 2px 10px rgba(0,0,0,.35);' +
      'max-width:480px;white-space:pre-wrap;word-break:break-all;cursor:pointer;', msg);
    t.id = '__jspjump_toast';
    t.title = '클릭하면 닫힘';
    t.onclick = function () { if (t.parentNode) { t.parentNode.removeChild(t); } };
    document.body.appendChild(t);
    setTimeout(t.onclick, ms || 4000);
  }
  state.toast = toast;

  /* ---------------------------------------------------------------- frames */

  function docs() {
    var out = [];
    (function walk(win) {
      var d;
      try { d = win.document; if (!d || !d.documentElement) { return; } } catch (e) { return; }
      out.push(d);
      for (var i = 0; i < win.frames.length; i++) { walk(win.frames[i]); }
    })(TOP);
    return out;
  }

  function frameOffset(doc) {
    var x = 0, y = 0, win = doc.defaultView;
    try {
      while (win && win !== TOP && win.frameElement) {
        var r = win.frameElement.getBoundingClientRect();
        x += r.left + (win.frameElement.clientLeft || 0);
        y += r.top + (win.frameElement.clientTop || 0);
        win = win.parent;
      }
    } catch (e) { /* cross-origin parent */ }
    return { x: x, y: y };
  }

  /* ---------------------------------------------------------------- clues */

  function norm(s) {
    return (s || '').replace(/ /g, ' ').replace(/\s+/g, ' ').trim();
  }

  function stripTags(s) {
    return norm(String(s || '').replace(/<br\s*\/?>/gi, ' ').replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' '));
  }

  function ownText(el) {
    if (!el) { return ''; }
    var tag = el.tagName;
    if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') { return ''; }
    var t = norm(el.innerText || el.textContent || '');
    return t.length > 60 ? '' : t;
  }

  function isOurs(el) {
    return el && el.getAttribute && el.getAttribute('data-jspjump') === '1';
  }

  /* resolve what the user actually means: the input behind a label, the button around an icon, ... */
  function resolve(el) {
    if (!el || el.nodeType !== 1) { return el; }
    if (el.tagName === 'LABEL' && el.control && !el.id) { return el.control; }
    if (el.id || el.name) { return el; }
    var n = el, i;
    for (i = 0; i < 3 && n && n.parentElement; i++) {
      n = n.parentElement;
      if (/^(BUTTON|A|SELECT|TEXTAREA)$/.test(n.tagName) || (n.tagName === 'INPUT')) { return n; }
      if (n.tagName === 'LABEL' && n.control && !n.id) { return n.control; }
    }
    return el;
  }

  function gridApi(el) {
    try { return el.ownerDocument.defaultView.AUIGrid || null; } catch (e) { return null; }
  }

  function gridInfo(el, cx, cy) {
    var api = gridApi(el);
    if (!api || !api.isCreated) { return null; }
    var n = el, container = null, pid = null;
    for (; n && n.nodeType === 1; n = n.parentElement) {
      if (n.id) {
        try { if (api.isCreated('#' + n.id)) { container = n; pid = '#' + n.id; break; } } catch (e) { /* ignore */ }
      }
    }
    if (!container) { return null; }
    var info = { gridId: container.id, part: (el.closest && el.closest('.aui-grid-header-panel')) ? 'header' : 'body' };
    var cols = [];
    try { cols = api.getColumnInfoList(pid) || []; } catch (e) { cols = []; }
    var byHeader = {};
    for (var i = 0; i < cols.length; i++) {
      var c = cols[i];
      if (!c || !c.dataField) { continue; }
      var h = stripTags(c.headerText);
      (byHeader[h] = byHeader[h] || []).push(c.dataField);
    }
    var cells = container.querySelectorAll('.aui-grid-header-panel td, .aui-grid-default-header');
    var best = null, bestW = 1e9;
    for (var j = 0; j < cells.length; j++) {
      var r = cells[j].getBoundingClientRect();
      if (r.width <= 0 || cx < r.left || cx > r.right) { continue; }
      var txt = norm(cells[j].innerText || cells[j].textContent || '');
      if (!txt || !byHeader[txt]) { continue; }
      if (r.width < bestW) { best = { text: txt, cell: cells[j] }; bestW = r.width; }
    }
    if (best) {
      info.headerText = best.text;
      var fields = byHeader[best.text];
      if (fields.length === 1) {
        info.dataField = fields[0];
      } else {
        /* same header text twice: pick by column order among visible header cells with that text */
        var same = [];
        for (var k = 0; k < cells.length; k++) {
          if (norm(cells[k].innerText || cells[k].textContent || '') === best.text &&
              cells[k].getBoundingClientRect().width > 0) { same.push(cells[k]); }
        }
        var idx = same.indexOf(best.cell);
        info.dataField = fields[idx >= 0 && idx < fields.length ? idx : 0];
      }
    } else if (info.part === 'header') {
      info.headerText = ownText(el);
    }
    return info;
  }

  function ancestorsOf(el) {
    var out = [], cur = el, n = el.parentElement, guard = 0;
    while (guard++ < 300) {
      if (!n) {
        /* continue through the iframe element into the parent document */
        try { n = cur.ownerDocument.defaultView.frameElement; } catch (e) { n = null; }
        if (!n) { break; }
      }
      if (n.id && !isOurs(n)) { out.push({ tag: n.tagName.toLowerCase(), id: n.id }); }
      if (out.length >= 10) { break; }
      cur = n;
      n = n.parentElement;
    }
    return out;
  }

  function clues(raw, cx, cy) {
    var el = resolve(raw);
    var doc = el.ownerDocument;
    var loc = doc.defaultView.location;
    var payload = {
      page: { url: loc.href, path: loc.pathname, title: doc.title || TOP.document.title },
      target: {
        tag: el.tagName.toLowerCase(),
        id: el.id || null,
        name: el.getAttribute('name') || null,
        type: el.getAttribute('type') || null,
        value: (el.tagName === 'INPUT' && /^(button|submit|reset)$/i.test(el.type)) ? norm(el.value) : null,
        text: ownText(el) || null,
        className: (typeof el.className === 'string' ? el.className : '') || null
      },
      ancestors: ancestorsOf(el)
    };
    var g = gridInfo(raw, cx, cy);
    if (g) {
      payload.grid = g;
      /* do not send grid cell data */
      if (g.part === 'body') { payload.target.text = null; }
    }
    return payload;
  }

  function describe(p) {
    var t = p.target, s = [];
    if (p.grid) {
      s.push('grid=' + p.grid.gridId);
      if (p.grid.dataField) { s.push('dataField=' + p.grid.dataField); }
      if (p.grid.headerText) { s.push('header="' + p.grid.headerText + '"'); }
    }
    if (t.id) { s.push('id=' + t.id); }
    if (t.name) { s.push('name=' + t.name); }
    if (t.value) { s.push('value="' + t.value + '"'); }
    if (t.text && !p.grid) { s.push('text="' + t.text + '"'); }
    if (!s.length) { s.push('<' + t.tag + '>'); }
    return s.join(', ');
  }

  /* ---------------------------------------------------------------- send */

  /* resolves with the parsed JSON, or null when nothing (or not JSP Jump) answers on that port */
  function request(method, url, body, timeout) {
    return new Promise(function (resolve) {
      var xhr = new XMLHttpRequest();
      xhr.open(method, url, true);
      /* text/plain keeps this a "simple" CORS request (no preflight) */
      if (body) { xhr.setRequestHeader('Content-Type', 'text/plain;charset=UTF-8'); }
      xhr.timeout = timeout;
      xhr.onload = function () {
        var r = null;
        try { r = JSON.parse(xhr.responseText); } catch (e) { r = null; }
        resolve(r);
      };
      xhr.onerror = xhr.ontimeout = function () { resolve(null); };
      xhr.send(body || null);
    });
  }

  function ping(port) {
    return request('GET', endpointOf(port) + '/ping', null, 1500).then(function (r) {
      return r && r.plugin === 'JSP Jump' ? { port: port, info: r } : null;
    });
  }

  function post(port, p, dryRun) {
    var body = {};
    for (var k in p) { if (Object.prototype.hasOwnProperty.call(p, k)) { body[k] = p[k]; } }
    body.dryRun = !!dryRun;
    return request('POST', endpointOf(port), JSON.stringify(body), 15000).then(function (r) {
      return r ? { port: port, res: r } : null;
    });
  }

  function preferredPorts() {
    var out = [];
    [rememberedPort(), servedPort, FIRST_PORT].forEach(function (pt) {
      if (pt && out.indexOf(pt) < 0) { out.push(pt); }
    });
    return out;
  }

  function allPorts(except) {
    var out = [];
    for (var pt = FIRST_PORT; pt <= LAST_PORT; pt++) { if (except.indexOf(pt) < 0) { out.push(pt); } }
    return out;
  }

  /*
   * 1) ask the usual IDE (remembered / serving port). Found -> open there.
   * 2) otherwise scan the other ports, search without opening (dryRun), open in the IDE with the best match.
   */
  function send(p) {
    if (fixedEndpoint) {
      request('POST', fixedEndpoint, JSON.stringify(p), 15000).then(function (r) { report(p, r); });
      return;
    }
    var first = preferredPorts();
    Promise.all(first.map(ping)).then(function (live) {
      live = live.filter(Boolean);
      var tryFirst = live.length ? live[0].port : null;
      var step1 = tryFirst ? post(tryFirst, p, false) : Promise.resolve(null);
      return step1.then(function (r1) {
        if (r1 && r1.res && r1.res.ok && r1.res.found) { return r1; }
        if (r1 && r1.res && r1.res.originRejected) { return r1; }
        var others = allPorts(tryFirst ? [tryFirst] : []);
        return Promise.all(others.map(ping)).then(function (more) {
          more = more.filter(Boolean);
          if (!more.length) { return r1; }
          return Promise.all(more.map(function (m) { return post(m.port, p, true); })).then(function (dry) {
            var best = null;
            dry.forEach(function (d) {
              if (d && d.res && d.res.ok && d.res.found && (!best || d.res.score > best.res.score)) { best = d; }
            });
            if (best) { return post(best.port, p, false); }
            return r1 || (dry.filter(Boolean)[0] || null);
          });
        });
      });
    }).then(function (r) {
      if (r && r.res && r.res.ok) { rememberPort(r.port); }
      report(p, r ? r.res : null);
    });
  }

  function report(p, r) {
    if (!r) {
      toast('JSP Jump: IntelliJ를 찾을 수 없습니다 (localhost:' + FIRST_PORT + '~' + LAST_PORT + ').' +
        '\n- IntelliJ가 실행 중이고 JSP Jump 플러그인이 설치돼 있는지 확인하세요.' +
        '\n- IDE의 Built-in server 포트를 바꿨다면 ' + FIRST_PORT + '~' + LAST_PORT + ' 사이로 두세요.', 'error', 9000);
      return;
    }
    if (!r.ok) {
      toast('JSP Jump: ' + (r.error || '오류') +
        (r.originRejected ? '\nIntelliJ 오른쪽 아래 알림에서 "Allow"를 누르면 이 주소도 허용됩니다.' : ''), 'error', 9000);
      return;
    }
    if (!r.found) {
      toast('JSP Jump: 찾지 못했습니다.\n단서: ' + describe(p) +
        (r.tried && r.tried.length ? '\n시도: ' + r.tried.join(' / ') : ''), 'warn', 8000);
      return;
    }
    var name = String(r.file).split('/').pop();
    if (r.found === 1) {
      toast('JSP Jump: ' + name + ':' + r.line + '\n(' + r.strategy + ' · ' + r.project + ')', 'ok', 3500);
    } else {
      toast('JSP Jump: 후보 ' + r.found + '개 - IDE에서 선택하세요\n(' + r.strategy + ' · 첫 후보 ' + name + ':' +
        r.line + ')', 'info', 5000);
    }
  }

  /* ---------------------------------------------------------------- events */

  var current = null;

  function highlight(el) {
    current = el;
    if (!el || !el.getBoundingClientRect) { box.style.display = tip.style.display = 'none'; return; }
    var r = el.getBoundingClientRect(), o = frameOffset(el.ownerDocument);
    box.style.left = (r.left + o.x) + 'px';
    box.style.top = (r.top + o.y) + 'px';
    box.style.width = r.width + 'px';
    box.style.height = r.height + 'px';
    box.style.display = 'block';
    var label = el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') +
      (el.getAttribute('name') ? ' [name=' + el.getAttribute('name') + ']' : '');
    var g = gridInfo(el, lastX, lastY);
    if (g) { label = 'AUIGrid ' + g.gridId + (g.dataField ? ' · ' + g.dataField : '') + (g.headerText ? ' "' + g.headerText + '"' : ''); }
    tip.textContent = label;
    tip.style.left = Math.max(0, r.left + o.x) + 'px';
    tip.style.top = Math.max(0, r.top + o.y - 22) + 'px';
    tip.style.display = 'block';
  }

  var lastX = 0, lastY = 0;

  function onMove(e) {
    lastX = e.clientX;
    lastY = e.clientY;
    var t = e.target;
    if (isOurs(t)) { return; }
    var r = resolve(t);
    if (r !== current) { highlight(r); } else if (gridApi(t)) { highlight(r); }
  }

  function block(e) {
    if (isOurs(e.target)) { return; }
    e.preventDefault();
    e.stopPropagation();
    if (e.stopImmediatePropagation) { e.stopImmediatePropagation(); }
  }

  function onClick(e) {
    if (isOurs(e.target)) { return; }
    block(e);
    var raw = e.target;
    var p;
    try { p = clues(raw, e.clientX, e.clientY); } catch (err) { stop(); toast('JSP Jump: 단서 수집 실패 - ' + err, 'error'); return; }
    stop();
    toast('JSP Jump: IDE로 전송 중...\n' + describe(p), 'info', 15000);
    send(p);
  }

  function onKey(e) {
    if (e.key === 'Escape' || e.keyCode === 27) {
      block(e);
      stop();
      toast('JSP Jump: 취소했습니다.', 'info', 1500);
    }
  }

  var BLOCKED = ['mousedown', 'mouseup', 'pointerdown', 'pointerup', 'dblclick', 'contextmenu', 'touchstart', 'touchend'];
  var attached = [];

  function attach() {
    var ds = docs();
    for (var i = 0; i < ds.length; i++) {
      var w = ds[i].defaultView;
      w.addEventListener('mousemove', onMove, true);
      w.addEventListener('click', onClick, true);
      w.addEventListener('keydown', onKey, true);
      for (var j = 0; j < BLOCKED.length; j++) { w.addEventListener(BLOCKED[j], block, true); }
      attached.push(w);
    }
  }

  function stop() {
    state.active = false;
    for (var i = 0; i < attached.length; i++) {
      var w = attached[i];
      try {
        w.removeEventListener('mousemove', onMove, true);
        w.removeEventListener('click', onClick, true);
        w.removeEventListener('keydown', onKey, true);
        for (var j = 0; j < BLOCKED.length; j++) { w.removeEventListener(BLOCKED[j], block, true); }
      } catch (e) { /* frame gone */ }
    }
    attached = [];
    [box, tip, banner].forEach(function (n) { if (n.parentNode) { n.parentNode.removeChild(n); } });
  }
  state.stop = stop;

  attach();
})();
