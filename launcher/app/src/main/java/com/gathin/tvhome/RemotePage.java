package com.gathin.tvhome;

/** The phone-side remote page, served by {@link RemoteServer}. Single file, no dependencies. */
final class RemotePage {

    private RemotePage() {
    }

    static final String HTML = """
            <!DOCTYPE html>
            <html lang="zh">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
            <title>TV 手机遥控</title>
            <style>
              * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
              html, body { margin: 0; height: 100%; background: #0b0e13; color: #eef1f5;
                font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
                overscroll-behavior: none; }
              body { display: flex; flex-direction: column; height: 100dvh; }
              header { display: flex; align-items: center; gap: 8px; padding: 12px 16px 4px; }
              header .title { font-size: 17px; font-weight: 600; }
              #dot { width: 9px; height: 9px; border-radius: 50%; background: #e5534b; }
              #dot.on { background: #3fb950; }
              #tvstate { margin-left: auto; font-size: 13px; color: #8b95a7; }
              #warn { display: none; margin: 8px 16px 0; padding: 10px 12px; border-radius: 10px;
                background: #3a2c12; color: #f0c169; font-size: 13px; line-height: 1.5; }
              #pad { flex: 1; margin: 12px 16px 8px; border-radius: 18px; background: #171c26;
                border: 1px solid #262e3f; touch-action: none; position: relative;
                display: flex; align-items: center; justify-content: center; }
              #pad span { color: #5b6678; font-size: 13px; text-align: center;
                line-height: 1.9; pointer-events: none; padding: 0 20px; }
              .row { display: flex; gap: 8px; padding: 4px 16px; }
              button { flex: 1; padding: 13px 0; font-size: 15px; color: #eef1f5; background: #1d2432;
                border: 1px solid #2b3448; border-radius: 12px; }
              button:active { background: #2b364d; }
              #dpad { display: none; padding: 6px 16px 0; }
              #dpad .grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; max-width: 340px; margin: 0 auto; }
              #dpad button { padding: 15px 0; font-size: 17px; }
              #dpad .ok { background: #21406e; border-color: #2f5aa0; font-size: 15px; }
              #kbbar { display: none; gap: 8px; padding: 6px 16px 2px; }
              #kbbar input { flex: 1; padding: 12px; font-size: 16px; border-radius: 12px;
                border: 1px solid #2b3448; background: #12161f; color: #eef1f5; }
              #kbbar button { flex: 0 0 auto; padding: 0 16px; }
              footer { padding: 8px 16px 16px; }
              #pin { position: fixed; inset: 0; background: #0b0e13; display: none;
                flex-direction: column; align-items: center; justify-content: center; gap: 14px; padding: 24px; }
              #pin h2 { margin: 0; font-size: 19px; }
              #pin p { margin: 0; color: #8b95a7; font-size: 14px; text-align: center; line-height: 1.6; }
              #pin input { width: 180px; padding: 13px; font-size: 22px; text-align: center; letter-spacing: 8px;
                border-radius: 12px; border: 1px solid #2b3448; background: #12161f; color: #eef1f5; }
              #pin button { flex: none; width: 180px; }
            </style>
            </head>
            <body>
            <header><div id="dot"></div><div class="title">TV 手机遥控</div><div id="tvstate">电视：离线</div></header>
            <div id="warn">电视上还没开启无障碍服务，触控板暂时用不了。<br>在电视上打开：设置 → 无障碍 → TV手机遥控 → 开启，然后回到这里。</div>
            <div id="pad"><span>单指滑动：移动指针<br>双指滑动：滚动页面<br>轻点：点击</span></div>
            <div id="dpad"><div class="grid">
              <div></div><button data-k="up">↑</button><div></div>
              <button data-k="left">←</button><button class="ok" data-k="ok">确定</button><button data-k="right">→</button>
              <div></div><button data-k="down">↓</button><div></div>
            </div></div>
            <div id="kbbar">
              <input id="kb" type="text" placeholder="在这里打字，会同步到电视" autocomplete="off">
              <button id="kbclose">收起</button>
            </div>
            <div class="row">
              <button id="bback">返回</button>
              <button id="bhome">主页</button>
              <button id="bdpad">方向键</button>
              <button id="bkb">键盘</button>
              <button id="bpower">电源</button>
            </div>
            <footer><div class="row" style="padding:0">
              <button id="bvoldn">音量 −</button>
              <button id="bmute">静音</button>
              <button id="bvolup">音量 ＋</button>
            </div></footer>
            <div id="pin">
              <h2>输入电视上的配对码</h2>
              <p>在电视启动器顶栏点手机图标，<br>弹窗里有 4 位配对码</p>
              <input id="pininput" type="tel" maxlength="4" placeholder="····">
              <button id="pinbtn">连接</button>
            </div>
            <script>
            var ws = null, token = localStorage.getItem('tvrt') || '', pollTimer = null;
            var urlKey = new URLSearchParams(location.search).get('k');
            if (urlKey) { token = urlKey; localStorage.setItem('tvrt', token); }
            var dot = document.getElementById('dot'), warn = document.getElementById('warn');
            var tvstate = document.getElementById('tvstate');
            function connect() {
              if (!token) { document.getElementById('pin').style.display = 'flex'; return; }
              ws = new WebSocket('ws://' + location.host + '/ws?token=' + encodeURIComponent(token));
              ws.onopen = function() {
                dot.className = 'on'; send({t: 'status'});
                clearInterval(pollTimer);
                pollTimer = setInterval(function() { send({t: 'status'}); }, 5000);
              };
              ws.onclose = function() {
                dot.className = ''; tvstate.textContent = '电视：离线';
                clearInterval(pollTimer);
                setTimeout(connect, 2500);
              };
              ws.onerror = function() { try { ws.close(); } catch (e) {} };
              ws.onmessage = function(ev) {
                var m = JSON.parse(ev.data);
                if (m.t === 'status') {
                  warn.style.display = m.a11y ? 'none' : 'block';
                  tvstate.textContent = m.screen === false ? '电视：关屏' : '电视：开屏';
                }
                if (m.t === 'textval') { var kb = document.getElementById('kb'); kb.value = m.s || ''; }
              };
            }
            function send(o) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(o)); }
            document.getElementById('pinbtn').onclick = function() {
              var pin = document.getElementById('pininput').value.trim();
              fetch('/auth?pin=' + encodeURIComponent(pin)).then(function(r) { return r.json(); })
              .then(function(j) {
                token = j.token; localStorage.setItem('tvrt', token);
                document.getElementById('pin').style.display = 'none'; connect();
              }).catch(function() { alert('配对码不对，再看看电视上的数字'); });
            };
            // ---- touchpad ----
            var pad = document.getElementById('pad');
            var pointers = new Map(), accX = 0, accY = 0, sAccX = 0, sAccY = 0;
            var downAt = 0, moved = 0, lastCentroid = null;
            pad.addEventListener('pointerdown', function(e) {
              pad.setPointerCapture(e.pointerId);
              pointers.set(e.pointerId, {x: e.clientX, y: e.clientY});
              if (pointers.size === 1) { downAt = Date.now(); moved = 0; }
              lastCentroid = centroid();
            });
            pad.addEventListener('pointermove', function(e) {
              if (!pointers.has(e.pointerId)) return;
              pointers.set(e.pointerId, {x: e.clientX, y: e.clientY});
              var c = centroid();
              if (!c || !lastCentroid) { lastCentroid = c; return; }
              var dx = c.x - lastCentroid.x, dy = c.y - lastCentroid.y;
              lastCentroid = c;
              moved += Math.abs(dx) + Math.abs(dy);
              if (pointers.size >= 2) { sAccX += dx; sAccY += dy; }
              else { accX += dx; accY += dy; }
            });
            function endPointer(e) {
              var wasSingle = pointers.size === 1;
              pointers.delete(e.pointerId);
              if (pointers.size === 0) {
                if (wasSingle && Date.now() - downAt < 300 && moved < 16) send({t: 'tap'});
                lastCentroid = null;
              } else {
                lastCentroid = centroid();
              }
            }
            pad.addEventListener('pointerup', endPointer);
            pad.addEventListener('pointercancel', endPointer);
            function centroid() {
              if (pointers.size === 0) return null;
              var x = 0, y = 0;
              pointers.forEach(function(p) { x += p.x; y += p.y; });
              return {x: x / pointers.size, y: y / pointers.size};
            }
            setInterval(function() {
              if (accX !== 0 || accY !== 0) { send({t: 'move', dx: accX, dy: accY}); accX = 0; accY = 0; }
              // Scroll goes out in meaningful chunks, not a drip of tiny gestures.
              if (Math.abs(sAccX) >= 40 || Math.abs(sAccY) >= 40) {
                send({t: 'scroll', dx: sAccX, dy: sAccY}); sAccX = 0; sAccY = 0;
              }
            }, 24);
            setInterval(function() {
              if (sAccX !== 0 || sAccY !== 0) { send({t: 'scroll', dx: sAccX, dy: sAccY}); sAccX = 0; sAccY = 0; }
            }, 400);
            // ---- buttons ----
            function key(id, k) {
              document.getElementById(id).addEventListener('pointerdown', function(e) {
                e.preventDefault(); send({t: 'key', k: k});
              });
            }
            key('bback', 'back'); key('bhome', 'home'); key('bpower', 'power');
            key('bvoldn', 'voldn'); key('bvolup', 'volup'); key('bmute', 'mute');
            document.querySelectorAll('#dpad button').forEach(function(b) {
              b.addEventListener('pointerdown', function(e) {
                e.preventDefault(); send({t: 'key', k: b.getAttribute('data-k')});
              });
            });
            var dpad = document.getElementById('dpad'), kbbar = document.getElementById('kbbar');
            document.getElementById('bdpad').onclick = function() {
              dpad.style.display = dpad.style.display === 'block' ? 'none' : 'block';
            };
            document.getElementById('bkb').onclick = function() {
              kbbar.style.display = 'flex';
              send({t: 'gettext'});
              setTimeout(function() { document.getElementById('kb').focus(); }, 60);
            };
            document.getElementById('kbclose').onclick = function() {
              kbbar.style.display = 'none'; document.getElementById('kb').blur();
            };
            var kbTimer = null;
            document.getElementById('kb').addEventListener('input', function(e) {
              clearTimeout(kbTimer);
              var v = e.target.value;
              kbTimer = setTimeout(function() { send({t: 'text', s: v}); }, 120);
            });
            document.getElementById('kb').addEventListener('keydown', function(e) {
              if (e.key === 'Enter') send({t: 'key', k: 'enter'});
            });
            connect();
            </script>
            </body>
            </html>
            """;
}
