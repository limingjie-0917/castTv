package com.bd.casttv.dlna

/**
 * 手机端 HTTP 页面 HTML 生成器：4 个页面共享同一套「设置弹窗」主题的内嵌 CSS。
 *
 * 设计对齐 App 端【设置弹窗】(dialog_settings.xml + bg_dialog_crayon_panel/header)：
 * - 背景：135° 渐变 #212E3A → #14171C → #100F16
 * - 卡片：#1B1F26 底 + 蜡笔黄 (#F6C445, ~40% 透明) 圆角描边 (radius 20dp)
 * - 顶部标题带：橙 → 黄 → 蓝 (crayon_orange #F2913D → crayon_yellow #F6C445 → crayon_blue #3E9BE8) 横向渐变
 * - 主色按钮：宝蓝 #4089FD 底 + 蜡笔黄描边 + 深色文字，焦点/悬停 dashed 描边
 * - 次要按钮 / 输入框：深色底 + 蜡笔黄半透明描边
 * - 交互提示：顶部横幅 "避免与电视同时编辑" (crayon_yellow)
 *
 * 全部页面均离线可用（内嵌 CSS+JS，只依赖同源 REST /api/xxx）。
 */
object HtmlPages {

    // 通用样式（每个页面 include）。使用 String rawString 保存以便被 Kotlin 编译。
    private val COMMON_CSS = """
        :root{
          --bg1:#212E3A;--bg2:#14171C;--bg3:#100F16;
          --card:#1B1F26;--card2:#12141A;
          --stroke:rgba(246,196,69,.42);--stroke-hi:#F6C445;
          --primary:#4089FD;--primary-dark:#2E63C4;
          --accent:#2FA8C9;--warn:#F2913D;--danger:#E8453C;--success:#43B97F;
          --text:#E8EAED;--sub:#A8B0BE;--hint:#5E6773;
          --y:#F6C445;--o:#F2913D;--r:#E8453C;--g:#43B97F;--b:#3E9BE8;
        }
        *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
        html,body{margin:0;padding:0;font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;color:var(--text);background:linear-gradient(135deg,var(--bg1) 0%,var(--bg2) 55%,var(--bg3) 100%);min-height:100vh;overscroll-behavior-x:contain}
        body{padding-bottom:96px;overscroll-behavior-x:contain;touch-action:pan-y;-webkit-user-select:none;user-select:none;-webkit-touch-callout:none}
        a{color:var(--y);text-decoration:none}
        .header{position:sticky;top:0;z-index:10;background:linear-gradient(90deg,var(--o) 0%,var(--y) 45%,var(--b) 100%);color:#101217;padding:14px 18px;border-radius:0 0 22px 22px;box-shadow:0 8px 24px rgba(0,0,0,.35);display:flex;align-items:center;justify-content:space-between}
        .header .brand{font-weight:800;font-size:18px;letter-spacing:.5px}
        .header .brand .emoji{margin-right:6px}
        .header .brand small{font-weight:500;opacity:.75;margin-left:6px}
        .nav{display:flex;gap:8px;padding:12px;overflow-x:auto;overflow-y:hidden;-webkit-overflow-scrolling:touch;touch-action:pan-x;overscroll-behavior-x:contain}
        .nav a{flex:0 0 auto;background:var(--card);border:1.5px dashed var(--stroke);color:var(--text);padding:10px 14px;border-radius:14px;font-size:14px;font-weight:600;transition:.15s}
        .nav a.active{background:var(--primary);color:#fff;border-color:var(--y);border-style:solid;box-shadow:0 4px 14px rgba(64,137,253,.35)}
        .banner{margin:10px 12px;padding:10px 14px;border-radius:14px;background:rgba(246,196,69,.12);border:1.5px dashed var(--stroke-hi);color:var(--y);font-size:13px;line-height:1.6}
        .banner .pin{margin-right:6px}
        .container{padding:0 12px;transition:transform .2s ease,opacity .2s ease;will-change:transform,opacity;touch-action:pan-y;overscroll-behavior-x:contain}
        .container.tab-slide-out-left{transform:translateX(-28px);opacity:.15}
        .container.tab-slide-out-right{transform:translateX(28px);opacity:.15}
        .container.tab-slide-in-left{animation:tabInLeft .2s ease}
        .container.tab-slide-in-right{animation:tabInRight .2s ease}
        .container.tab-boundary-left{animation:tabBoundaryLeft .2s ease}
        .container.tab-boundary-right{animation:tabBoundaryRight .2s ease}
        @keyframes tabInLeft{from{transform:translateX(-28px);opacity:.15}to{transform:translateX(0);opacity:1}}
        @keyframes tabInRight{from{transform:translateX(28px);opacity:.15}to{transform:translateX(0);opacity:1}}
        @keyframes tabBoundaryLeft{0%{transform:translateX(0)}45%{transform:translateX(8px)}100%{transform:translateX(0)}}
        @keyframes tabBoundaryRight{0%{transform:translateX(0)}45%{transform:translateX(-8px)}100%{transform:translateX(0)}}
        .card{background:var(--card);border:1.5px solid var(--stroke);border-radius:20px;padding:14px;margin-bottom:12px;box-shadow:0 4px 14px rgba(0,0,0,.24)}
        .card h3{margin:0 0 10px;font-size:16px;color:var(--y);display:flex;align-items:center;gap:6px}
        .row{display:flex;align-items:center;gap:10px}
        .row+.row{margin-top:10px}
        .grow{flex:1;min-width:0}
        .muted{color:var(--sub);font-size:12px}
        .title{font-size:15px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
        .subtitle{font-size:12px;color:var(--sub);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin-top:2px}
        .btn{border:1.5px solid var(--stroke);background:var(--card2);color:var(--text);padding:8px 12px;border-radius:12px;font-size:13px;font-weight:600;cursor:pointer;transition:.15s}
        .btn:hover,.btn:focus{border-style:dashed;border-color:var(--y);background:rgba(47,168,201,.18);outline:none}
        .btn.primary{background:var(--primary);border-color:var(--y);color:#fff}
        .btn.primary:hover,.btn.primary:focus{background:var(--primary-dark)}
        .btn.warn{background:transparent;color:var(--o);border-color:rgba(242,145,61,.55)}
        .btn.danger{background:transparent;color:var(--r);border-color:rgba(232,69,60,.55)}
        .btn.success{background:transparent;color:var(--g);border-color:rgba(67,185,127,.55)}
        .btn.sm{padding:6px 10px;font-size:12px;border-radius:10px}
        .btn+.btn{margin-left:6px}
        .empty{text-align:center;color:var(--sub);padding:36px 20px;font-size:14px}
        .empty .emoji{font-size:36px;display:block;margin-bottom:10px}
        input[type=text],input[type=password],input[type=file],textarea,select{width:100%;background:#181B22;border:1.5px solid var(--stroke);color:var(--text);padding:10px 12px;border-radius:12px;font-size:14px;outline:none}
        input:focus,textarea:focus,select:focus{border-style:dashed;border-color:var(--y)}
        .tag{display:inline-block;padding:2px 8px;border-radius:8px;font-size:11px;font-weight:700;margin-left:6px}
        .tag.pending{background:rgba(62,155,232,.2);color:var(--b)}
        .tag.playing{background:rgba(67,185,127,.22);color:var(--g)}
        .tag.finished{background:rgba(94,103,115,.28);color:var(--sub)}
        .tag.default{background:rgba(246,196,69,.22);color:var(--y)}
        .tag.preset{background:rgba(242,145,61,.22);color:var(--o)}
        .col-toolbar{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:10px}
        .item-row{padding:10px;border-radius:14px;background:var(--card2);border:1px solid rgba(246,196,69,.15);margin-bottom:8px;display:flex;align-items:center;gap:10px}
        .favorite-item-row{align-items:stretch;flex-direction:column;gap:8px}
        .item-row.playing{border-color:var(--g);box-shadow:0 0 0 1px rgba(67,185,127,.4)}
        .item-actions{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px}
        .favorite-item-actions{margin-top:0;justify-content:flex-start}
        .footer-note{text-align:center;color:var(--hint);font-size:11px;padding:14px}
        .toast{position:fixed;top:78px;left:50%;transform:translateX(-50%);background:rgba(16,18,23,.95);color:var(--y);padding:10px 18px;border-radius:14px;border:1.5px dashed var(--y);font-size:13px;z-index:100;opacity:0;transition:.2s;pointer-events:none;max-width:90vw}
        .toast.show{opacity:1}
        .virtual-tv{margin:12px;border-radius:22px;border:1.5px solid var(--stroke);background:linear-gradient(135deg,rgba(33,46,58,.96),rgba(16,15,22,.96));padding:12px;box-shadow:0 6px 20px rgba(0,0,0,.32)}
        .virtual-tv-bar{display:flex;align-items:center;gap:10px;cursor:pointer;user-select:none}
        .virtual-tv-title{flex:1;min-width:0;font-weight:800;color:var(--y);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:14px}
        .virtual-tv-sub{font-size:11px;color:var(--sub);margin-top:2px}
        .tv-actions{display:flex;align-items:center;gap:8px}.tv-toggle{color:var(--y);font-weight:900;font-size:16px}
        .tv-frame{margin-top:12px;border-radius:18px;padding:12px;background:linear-gradient(90deg,var(--o) 0%,var(--y) 48%,var(--b) 100%);box-shadow:inset 0 0 0 1px rgba(16,18,23,.35)}
        .tv-screen{position:relative;aspect-ratio:16/9;border-radius:12px;background:#070809;border:3px solid #101217;overflow:hidden;display:flex;align-items:center;justify-content:center;text-align:center;color:var(--text);font-weight:800;padding:18px;line-height:1.4}
        .tv-screen img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}.tv-screen .fallback{position:relative;z-index:1;text-shadow:0 2px 10px rgba(0,0,0,.7)}
        .tv-stand{width:76px;height:10px;border-radius:0 0 12px 12px;background:var(--y);margin:0 auto}.tv-foot{width:140px;height:8px;border-radius:12px;background:#101217;margin:6px auto 0;border:1px solid rgba(246,196,69,.5)}
        .spinner{display:none;width:16px;height:16px;border:2px solid rgba(246,196,69,.25);border-top-color:var(--y);border-radius:50%;animation:spin .8s linear infinite}.loading .spinner{display:inline-block}@keyframes spin{to{transform:rotate(360deg)}}
        .virtual-tv.collapsed .tv-frame,.virtual-tv.collapsed .tv-stand,.virtual-tv.collapsed .tv-foot{display:none}
        .modal-mask{position:fixed;inset:0;background:rgba(0,0,0,.72);display:flex;align-items:center;justify-content:center;z-index:50;padding:16px}
        .modal-mask.hidden{display:none}
        .modal{background:linear-gradient(135deg,rgba(33,46,58,.98),rgba(16,15,22,.98));border:1.5px solid var(--stroke-hi);border-radius:22px;padding:20px;max-width:400px;width:100%;box-shadow:0 12px 32px rgba(0,0,0,.5)}
        .modal h3{margin:0 0 12px;color:var(--y);font-size:17px}
        .drag-handle{cursor:grab;color:var(--y);font-size:20px;user-select:none;padding:0 6px}
        .dragging{opacity:.45}
        details{border:1.5px solid var(--stroke);border-radius:16px;background:var(--card2);padding:0 12px;margin-bottom:10px;overflow:hidden}
        details[open]{border-color:var(--y);border-style:dashed}
        details>summary{list-style:none;padding:12px 0;font-weight:700;color:var(--y);cursor:pointer;display:flex;align-items:center;justify-content:space-between;gap:8px}
        details>summary::-webkit-details-marker{display:none}
        .switch{position:relative;display:inline-block;width:44px;height:24px}
        .switch input{opacity:0;width:0;height:0}
        .slider{position:absolute;cursor:pointer;inset:0;background:#3A3F4A;border-radius:24px;transition:.2s}
        .slider:before{position:absolute;content:"";height:18px;width:18px;left:3px;top:3px;background:#fff;border-radius:50%;transition:.2s}
        input:checked+.slider{background:var(--primary)}
        input:checked+.slider:before{transform:translateX(20px)}
        .tab-loading{position:fixed;left:12px;right:12px;bottom:18px;z-index:80;display:none;align-items:center;justify-content:center;gap:10px;padding:12px 14px;border-radius:16px;background:rgba(16,18,23,.96);border:1.5px dashed var(--y);color:var(--y);font-size:14px;font-weight:800;box-shadow:0 8px 24px rgba(0,0,0,.35)}
        .tab-loading.show{display:flex}
        .tab-loading:before{content:"";width:18px;height:18px;border:2px solid rgba(246,196,69,.25);border-top-color:var(--y);border-radius:50%;animation:spin .8s linear infinite}
        .container{max-width:640px;margin:0 auto}
    """.trimIndent()

    // 公共顶部 + 提示 banner
    private fun frame(active: String, title: String, bodyHtml: String, extraScript: String = ""): String {
        val activeMark = { name: String -> if (active == name) "active" else "" }
        return """
        <!doctype html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8"/>
          <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"/>
          <title>$title · 小新的TV</title>
          <style>$COMMON_CSS</style>
        </head>
        <body>
          <div class="header">
            <div class="brand"><span class="emoji">🖍</span>小新的TV<small>· 手机遥控</small></div>
            <div style="font-size:12px;font-weight:700;color:#101217">🎬</div>
          </div>
          <section id="virtualTv" class="virtual-tv">
            <div class="virtual-tv-bar" onclick="toggleVirtualTv()">
              <div class="grow">
                <div id="vtvTitle" class="virtual-tv-title">📺 暂无播放内容</div>
                <div id="vtvSub" class="virtual-tv-sub">点击刷新获取电视端播放器状态</div>
              </div>
              <div class="tv-actions">
                <span id="vtvSpinner" class="spinner"></span>
                <button class="btn sm" onclick="event.stopPropagation();refreshVirtualTv()">刷新</button>
                <span id="vtvToggle" class="tv-toggle">⌃</span>
              </div>
            </div>
            <div class="tv-frame">
              <div id="vtvScreen" class="tv-screen"><div class="fallback">📺<br/>暂无播放内容</div></div>
            </div>
            <div class="tv-stand"></div><div class="tv-foot"></div>
          </section>
          <div class="nav">
            <a href="/" class="${activeMark("guide")}" data-tab="guide">📖 功能说明</a>
            <a href="/favorites" class="${activeMark("favorites")}" data-tab="favorites">⭐ 我的收藏</a>
            <a href="/history" class="${activeMark("history")}" data-tab="history">🕒 历史记录</a>
            <a href="/queue" class="${activeMark("queue")}" data-tab="queue">📼 稍后播放</a>
            <a href="/parse" class="${activeMark("parse")}" data-tab="parse">🌐 解析</a>
            <a href="/cloud" class="${activeMark("cloud")}" data-tab="cloud">☁️ 云同步</a>
            <a href="/wallpaper" class="${activeMark("wallpaper")}" data-tab="wallpaper">🖼 壁纸上传</a>
          </div>
          <div class="container">$bodyHtml</div>


          <div class="footer-note">🍰 小新的TV · v1.0.90 · 局域网离线服务</div>
          <div id="toast" class="toast"></div>
          <div id="tabLoading" class="tab-loading">正在加载当前栏目…</div>
          <script>
            const api={
              get:async u=>{
                const r=await fetch(u,{headers:{'Accept':'application/json'}});
                const ct=(r.headers.get('content-type')||'');
                if(!r.ok){let t='';try{t=await r.text();}catch(e){}throw new Error('HTTP '+r.status+(t?(' · '+t.slice(0,120)):''));}
                if(ct.indexOf('application/json')<0){let t='';try{t=await r.text();}catch(e){}throw new Error('返回非JSON('+ct+')'+(t?(' · '+t.slice(0,120)):''));}
                return r.json();
              },
              post:async(u,body)=>{
                const r=await fetch(u,{method:'POST',headers:{'Content-Type':'application/json; charset=utf-8'},body:JSON.stringify(body||{})});
                if(!r.ok){let t='';try{t=await r.text();}catch(e){}throw new Error('HTTP '+r.status+(t?(' · '+t.slice(0,120)):''));}
                return r.json();
              }
            };
            function toast(msg,ms){msg=msg||'';const t=document.getElementById('toast');t.textContent=msg;t.classList.add('show');setTimeout(()=>t.classList.remove('show'),ms||1600);}
            function playItem(title,uri){if(!uri){toast('播放失败');return;}api.post('/api/player/play',{uri:uri,title:title||''}).then(function(){toast('已在电视端播放…')}).catch(function(){toast('播放失败')});}
            function esc(s){s=(s==null)?'':String(s);return s.replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c]);}
            function fmtTime(ts){if(!ts)return '';const d=new Date(ts);const pad=n=>(''+n).padStart(2,'0');return d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate())+' '+pad(d.getHours())+':'+pad(d.getMinutes());}
            function toggleVirtualTv(){const v=document.getElementById('virtualTv');v.classList.toggle('collapsed');document.getElementById('vtvToggle').textContent=v.classList.contains('collapsed')?'⌄':'⌃';}
            function setVirtualTv(data){
              const titleEl=document.getElementById('vtvTitle'),sub=document.getElementById('vtvSub'),screen=document.getElementById('vtvScreen');
              const title=(data&&data.title)||'';const uri=(data&&data.uri)||'';const state=(data&&data.state)||'NO_MEDIA_PRESENT';const has=data&&data.hasContent;
              if(!has){titleEl.textContent='📺 暂无播放内容';sub.textContent='电视端当前没有播放资源';screen.innerHTML='<div class="fallback">🖍<br/>暂无播放内容</div>';return;}
              titleEl.textContent='📺 '+(title||uri||'正在播放');sub.textContent='状态：'+state;
              if(data.hasThumbnail&&data.thumbnailUrl){screen.innerHTML='<img src="'+data.thumbnailUrl+'" onerror="this.remove();document.getElementById(\'vtvScreen\').innerHTML=\'<div class=&quot;fallback&quot;>'+esc(title||uri)+'<br/><span class=&quot;muted&quot;>暂无缩略图</span></div>\'"/><div class="fallback" style="background:linear-gradient(transparent,rgba(0,0,0,.55));align-self:flex-end;width:100%;padding:12px;margin:-18px"><span>'+esc(title||uri)+'</span></div>';}
              else{screen.innerHTML='<div class="fallback">'+esc(title||uri)+'<br/><span class="muted">暂无缩略图</span></div>';}
            }
            async function refreshVirtualTv(){
              const box=document.getElementById('virtualTv');box.classList.add('loading');
              try{const d=await api.get('/api/player/status');setVirtualTv(d);toast('已刷新电视状态');}
              catch(e){document.getElementById('vtvTitle').textContent='📺 无法连接到电视';document.getElementById('vtvSub').textContent='请确认电视端 App 正在运行，且手机与电视在同一局域网';document.getElementById('vtvScreen').innerHTML='<div class="fallback">⚠️<br/>无法连接到电视</div>';toast('无法连接到电视',2400);}
              finally{box.classList.remove('loading');}
            }
            async function loadTab(url,tab,dir,fromPop){
              const needsLoading=['favorites','history','cloud','queue','wallpaper','parse'].includes(tab);const loader=document.getElementById('tabLoading');
              const curBox=document.querySelector('.container');
              if(curBox&&dir){curBox.classList.remove('tab-slide-in-left','tab-slide-in-right','tab-slide-out-left','tab-slide-out-right');void curBox.offsetWidth;curBox.classList.add(dir<0?'tab-slide-out-left':'tab-slide-out-right');}
              if(needsLoading){loader.textContent='正在加载'+({'favorites':'收藏','history':'历史记录','cloud':'云同步','queue':'稍后播放','wallpaper':'壁纸上传','parse':'解析'}[tab]||'当前栏目')+'…';loader.classList.add('show');}
              try{
                if(window.__tabTimer){clearInterval(window.__tabTimer);window.__tabTimer=null;}
                const resp=await fetch(url,{headers:{'X-Requested-With':'fetch'}});
                if(!resp.ok){throw new Error('HTTP '+resp.status);}
                const html=await resp.text();
                const doc=new DOMParser().parseFromString(html,'text/html');
                const next=doc.querySelector('.container');const cur=document.querySelector('.container');
                if(next&&cur){cur.innerHTML=next.innerHTML;if(dir){cur.classList.remove('tab-slide-out-left','tab-slide-out-right','tab-slide-in-left','tab-slide-in-right');void cur.offsetWidth;cur.classList.add(dir<0?'tab-slide-in-right':'tab-slide-in-left');setTimeout(function(){cur.classList.remove('tab-slide-in-left','tab-slide-in-right');},220);}}
                document.querySelectorAll('.nav a').forEach(a=>a.classList.toggle('active',a.dataset.tab===tab));
                if (typeof __updatePageSwitchButtons === 'function') __updatePageSwitchButtons();
                if(!fromPop){history.pushState({tab:tab,url:url},'',url);}document.title=(doc.querySelector('title')||{}).textContent||document.title;
                const scripts=[...doc.querySelectorAll('script')];const pageScript=scripts[scripts.length-1];
                // 根因修复：marker 的字符串字面量本身与下方分隔注释完全相同，txt.indexOf(marker)
                // 会命中本函数源码里 marker 的「定义处」这一更靠前的相同串，导致 substring 从半句
                // (';const txt=...) 开始，eval 到页面脚本首个 var 时报 "unexpected token var"。
                // 用拼接写法让定义处不再包含连续的分隔串，并用 lastIndexOf 双保险只取真正的分隔注释。
                if(pageScript){var marker='//__PAGE'+'_SCRIPT_START__';var txt=pageScript.textContent;var idx=txt.lastIndexOf(marker);if(idx>=0)(0,eval)(txt.substring(idx+marker.length).replace(/\blet\s+/g,'var '));}
              }catch(e){console&&console.warn&&console.warn('loadTab failed',url,e);toast('加载失败，请重试 🔄'+(e&&e.message?('（'+e.message+'）'):''),3000);}
              finally{loader.classList.remove('show');bindSwipeGesture();}
            }
            document.addEventListener('click',e=>{const a=e.target.closest&&e.target.closest('.nav a[data-tab]');if(!a)return;e.preventDefault();loadTab(a.getAttribute('href'),a.dataset.tab);});
            var __swipeStartX=0;var __swipeStartY=0;var __swipeStartT=0;var __swipeHorizontal=false;var __swipeTracking=false;
            function __currentTab(){var a=document.querySelector('.nav a.active[data-tab]');return a?{tab:a.dataset.tab,url:a.getAttribute('href')}: {tab:'guide',url:'/'};}
            function __tabIndex(){var tabs=document.querySelectorAll('.nav a[data-tab]');for(var i=0;i<tabs.length;i++){if(tabs[i].classList.contains('active'))return i;}return 0;}
            function __shakeTabBoundary(dir){var box=document.querySelector('.container');if(!box)return;var cls=dir<0?'tab-boundary-right':'tab-boundary-left';box.classList.remove('tab-boundary-left','tab-boundary-right');void box.offsetWidth;box.classList.add(cls);setTimeout(function(){box.classList.remove(cls);},220);}
            function __switchTabBySwipe(dir){var tabs=document.querySelectorAll('.nav a[data-tab]');if(!tabs.length)return;var idx=__tabIndex();var next=idx+dir;if(next<0||next>=tabs.length){__shakeTabBoundary(dir);return;}var a=tabs[next];loadTab(a.getAttribute('href'),a.dataset.tab,dir);}

            function __swipeTarget(){return document.body;}
            function __onSwipeStart(e){if(e.touches.length>1)return;if(e.target.closest&&e.target.closest('.nav')){__swipeTracking=false;return;}var t=e.touches[0];__swipeStartX=t.clientX;__swipeStartY=t.clientY;__swipeStartT=Date.now();__swipeHorizontal=false;__swipeTracking=true;}
            function __onSwipeMove(e){if(!__swipeTracking||e.touches.length>1)return;if(e.target.closest&&e.target.closest('.nav'))return;var t=e.touches[0];var dx=t.clientX-__swipeStartX;var dy=t.clientY-__swipeStartY;if(Math.abs(dx)>12&&Math.abs(dx)>Math.abs(dy)*1.2){__swipeHorizontal=true;e.preventDefault();}}
            function __onSwipeCancel(e){__swipeTracking=false;__swipeHorizontal=false;}
            function __onSwipeEnd(e){if(!__swipeTracking||e.changedTouches.length<1)return;var t=e.changedTouches[0];var dx=t.clientX-__swipeStartX;var dy=t.clientY-__swipeStartY;var dt=Date.now()-__swipeStartT;__swipeTracking=false;console&&console.log&&console.log('[swipe] dx=',dx,'dy=',dy);if(Math.abs(dx)>40&&Math.abs(dx)>Math.abs(dy)*1.5&&dt<800){e.preventDefault();__switchTabBySwipe(dx>0?-1:1);}}
            function bindSwipeGesture(){var box=__swipeTarget();if(!box)return;box.removeEventListener('touchstart',__onSwipeStart);box.removeEventListener('touchmove',__onSwipeMove);box.removeEventListener('touchend',__onSwipeEnd);box.removeEventListener('touchcancel',__onSwipeCancel);box.addEventListener('touchstart',__onSwipeStart,{passive:false});box.addEventListener('touchmove',__onSwipeMove,{passive:false});box.addEventListener('touchend',__onSwipeEnd,{passive:false});box.addEventListener('touchcancel',__onSwipeCancel,{passive:false});}
            (function(){
              var cur=__currentTab();
              try{history.replaceState({tab:cur.tab,url:cur.url},'',location.href);}catch(e){}
              bindSwipeGesture();
            })();
            window.addEventListener('popstate',function(e){if(e.state&&e.state.tab&&e.state.url){loadTab(e.state.url,e.state.tab,0,true);}});
            //__PAGE_SCRIPT_START__
            $extraScript
          </script>
        </body>
        </html>
        """.trimIndent()
    }

    // ======================================== GUIDE
    fun home(): String {
        val body = """
        <div class="banner"><span class="pin">📌</span>手机端与电视端双写：避免与电视同时编辑，以防修改覆盖。</div>
        <div class="card">
          <h3>👋 欢迎使用小新的TV · 手机遥控</h3>
          <div class="muted" style="line-height:1.7">
            手机端可以查看、编辑电视端的收藏、历史，还可以把内容加入「稍后播放队列」，然后一键推送到电视自动连播。
          </div>
        </div>
        <a class="card" href="/favorites" style="display:block;text-decoration:none;color:inherit">
          <h3>⭐ 我的收藏</h3>
          <div class="muted">查看/编辑合集数据 · 支持导入导出 CSV · 加入稍后播放</div>
        </a>
        <a class="card" href="/history" style="display:block;text-decoration:none;color:inherit">
          <h3>🕒 历史记录</h3>
          <div class="muted">查看/删除历史 · 加入稍后播放</div>
        </a>
        <a class="card" href="/queue" style="display:block;text-decoration:none;color:inherit">
          <h3>📼 稍后播放</h3>
          <div class="muted">拖动排序 · 覆盖 / 追加推送到电视 · 状态可视化</div>
        </a>
        <a class="card" href="/parse" style="display:block;text-decoration:none;color:inherit">
          <h3>🌐 网页解析播放</h3>
          <div class="muted">粘贴影片列表页或详情页网址，发送到电视端解析并播放</div>
        </a>
        <a class="card" href="/cloud" style="display:block;text-decoration:none;color:inherit">
          <h3>☁️ 云同步</h3>
          <div class="muted">从手机网页触发收藏上传 / 下载，与 Gitee 云端保持同步</div>
        </a>
        """
        return frame("guide", "功能说明", body)
    }

    // ======================================== PARSE
    fun parse(): String {
        val body = """
        <div class="banner"><span class="pin">🌐</span>请粘贴影片列表页或详情页网址，电视端会按所选类型解析并加载影片信息。</div>
        <div class="card">
          <h3>🌐 网页解析播放</h3>
          <div class="muted" style="line-height:1.7">支持常见 MacCMS 详情页（含 player_aaaa）、Cupfox 类列表页与通用网页兜底解析。提交后请看电视端的「网页解析播放」页面。</div>
          <div style="margin-top:14px">
            <input id="parseUrl" type="text" placeholder="粘贴影片列表页或详情页网址" autocomplete="off" />
          </div>
          <div class="row" style="margin-top:14px;gap:10px;align-items:center">
            <label class="btn sm" style="display:inline-flex;align-items:center;gap:6px"><input type="radio" name="parseType" value="list" checked />影片列表页</label>
            <label class="btn sm" style="display:inline-flex;align-items:center;gap:6px"><input type="radio" name="parseType" value="detail" />影片详情页</label>
          </div>
          <div class="row" style="margin-top:14px">
            <button class="btn primary" onclick="submitParseUrl()">开始解析</button>
            <button class="btn sm" onclick="clearParseUrl()">清空</button>
          </div>
          <div id="parseResult" class="muted" style="margin-top:12px;line-height:1.7"></div>
        </div>
        """.trimIndent()
        val script = """
        var parseSubmitting=false;
        function clearParseUrl(){document.getElementById('parseUrl').value='';document.getElementById('parseResult').textContent='';}
        function submitParseUrl(){
          var input=document.getElementById('parseUrl');
          var out=document.getElementById('parseResult');
          var url=(input&&input.value?input.value:'').trim();
          var typeInput=document.querySelector('input[name="parseType"]:checked');
          var type=(typeInput&&typeInput.value?typeInput.value:'detail');
          if(!url){toast('请先粘贴影片列表页或详情页网址');return;}
          if(url.indexOf('http://')!==0&&url.indexOf('https://')!==0){toast('请输入 http/https 开头的网址');return;}
          if(parseSubmitting){return;}
          parseSubmitting=true;out.textContent='正在发送到电视端…';
          api.post('/api/web_parse/submit',{url:url,type:type}).then(function(r){
            out.textContent=(r&&r.message)||'已发送到电视端';
            toast('已发送到电视端，请查看电视端解析进度',2400);
          }).catch(function(e){
            out.textContent='发送失败：'+(e&&e.message?e.message:'未知错误');
            toast('发送失败，请重试',2400);
          }).finally(function(){parseSubmitting=false;});
        }
        """.trimIndent()
        return frame("parse", "网页解析播放", body, script)
    }

    fun uploadJsonAdapter(): String {
        val body = """
        <div class="banner"><span class="pin">🧩</span>将 AI 生成的 JSON 解析规则粘贴到下方，保存后电视端会自动使用新规则重新解析当前网址。</div>
        <div class="card">
          <h3>📄 AI 规范文档</h3>
          <div class="muted" style="line-height:1.7">需要让 AI 生成规则时，可先下载精简版规范文档，再连同当前页面 URL 与页面源码一起发给 AI 工具。</div>
          <div class="row" style="margin-top:14px;gap:10px;align-items:center">
            <label class="btn sm" style="display:inline-flex;align-items:center;gap:6px"><input type="radio" name="jsonPageKind" value="list" />影片列表页</label>
            <label class="btn sm" style="display:inline-flex;align-items:center;gap:6px"><input type="radio" name="jsonPageKind" value="detail" checked />影片详情页</label>
          </div>
          <div class="row" style="margin-top:14px">
            <a id="jsonSpecDownload" class="btn primary" href="/download-json-adapter-spec?pageKind=detail">📄 下载 AI 规范文档</a>
            <a class="btn sm" href="/parse">返回网页解析</a>
          </div>
        </div>
        <div class="card">
          <h3>粘贴 JSON 规则</h3>
          <div class="muted" style="line-height:1.7">规则需包含 match、sources、playResolve 必要字段。保存成功后，TV 端会立即使用该规则解析当前输入的网址。</div>
          <form action="/upload-json-adapter-text" method="post" onsubmit="return beforeJsonTextSubmit()" style="margin-top:14px">
            <input id="jsonPageKindHidden" type="hidden" name="pageKind" value="detail" />
            <textarea id="jsonText" name="jsonText" rows="8" placeholder="将 AI 生成的 JSON 规则粘贴到这里…"></textarea>
            <div class="row" style="margin-top:14px">
              <button class="btn primary" type="submit">保存并解析</button>
            </div>
          </form>
          <div id="jsonTextTip" class="muted" style="margin-top:12px;line-height:1.7"></div>
        </div>
        """.trimIndent()
        val script = """
        function currentJsonPageKind(){var checked=document.querySelector('input[name="jsonPageKind"]:checked');return checked&&checked.value==='list'?'list':'detail';}
        function updateJsonPageKind(){
          var pageKind=currentJsonPageKind();
          var link=document.getElementById('jsonSpecDownload');
          var hidden=document.getElementById('jsonPageKindHidden');
          if(link){link.href='/download-json-adapter-spec?pageKind='+pageKind;}
          if(hidden){hidden.value=pageKind;}
        }
        function beforeJsonTextSubmit(){
          updateJsonPageKind();
          var input=document.getElementById('jsonText');
          var out=document.getElementById('jsonTextTip');
          var text=(input&&input.value?input.value:'').trim();
          if(!text){toast('请先粘贴 JSON 内容');return false;}
          if(out){out.textContent='正在保存，请稍候…';}
          return true;
        }
        document.querySelectorAll('input[name="jsonPageKind"]').forEach(function(el){el.addEventListener('change',updateJsonPageKind);});
        updateJsonPageKind();
        """.trimIndent()
        return frame("parse", "导入 JSON 解析规则", body, script)
    }

    fun uploadJsonAdapterResult(ok: Boolean, message: String): String {
        val safeMessage = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val cls = if (ok) "success" else "danger"
        val title = if (ok) "导入成功" else "导入失败"
        val body = """
        <div class="card">
          <h3>🧩 $title</h3>
          <div class="muted" style="line-height:1.8">$safeMessage</div>
          <div class="row" style="margin-top:14px">
            <a class="btn primary" href="/upload-json-adapter">继续上传</a>
            <a class="btn $cls" href="/parse">返回网页解析</a>
          </div>
        </div>
        """.trimIndent()
        return frame("parse", title, body)
    }

    // ======================================== CLOUD
    // v1.1.112：手机端 HTTP 云同步页重构，与 TV 端 CloudSyncDialog 语义对齐：
    //  - 上传 Tab：展示全部本地合集；private 置灰 + 点击提示；shared 可勾选；右侧 🔒/🔓 切换本次加锁。
    //  - 下载 Tab：预置合集独立分组可直接勾选；非预置只展示 shared；有锁项一次验证解锁本页全部；超上限拦截。
    // 内联 JS 仅用 var / function 声明（避免 loadTab 的 eval 二次执行时 let/const 重复声明报错），
    // 全程使用字符串拼接而非模板字符串，规避 Kotlin `${...}` 转义与 "unexpected token" 问题。
    fun cloud(): String {
        val body = """
        <div class="banner"><span class="pin">☁️</span>云同步会直接读写电视端收藏数据，请避免与电视端同时编辑收藏。</div>
        <div class="card">
          <h3>☁️ 云同步</h3>
          <div class="nav" style="padding:0;margin-bottom:12px">
            <a href="javascript:;" id="ctabUp" class="active" onclick="switchCloudTab('upload')">⬆️ 上传</a>
            <a href="javascript:;" id="ctabDown" onclick="switchCloudTab('download')">⬇️ 下载</a>
          </div>
          <div id="cloudUpload">
            <div class="muted" style="line-height:1.7">自己创建的合集，可进行加密上传<br/>其他用户设为共享的合集，可修改内容上传（不可操作加密）</div>
            <div id="upList" style="margin-top:10px"><div class="muted">正在加载本地合集…</div></div>
            <div class="row" style="margin-top:14px">
              <button class="btn primary" onclick="doUpload()">⬆️ 上传所选合集</button>
              <button class="btn sm" onclick="loadUpload()">🔄 刷新</button>
            </div>
          </div>
          <div id="cloudDownload" style="display:none">
            <div class="muted" id="downHint" style="line-height:1.7">正在加载云端合集…</div>
            <div id="downList" style="margin-top:10px"><div class="muted">正在加载云端合集…</div></div>
            <div class="row" style="margin-top:14px">
              <button class="btn primary" onclick="doDownload()">⬇️ 下载所选合集</button>
              <button class="btn sm" onclick="loadDownload()">🔄 刷新</button>
            </div>
          </div>
        </div>

        <div id="pwdModal" class="modal-mask hidden" onclick="if(event.target===this)hidePwd()">
          <div class="modal">
            <h3 id="pwdTitle">请输入密码</h3>
            <input id="pwdInput" type="password" placeholder="请输入密码"/>
            <div style="margin-top:14px;text-align:right">
              <button class="btn" onclick="hidePwd()">取消</button>
              <button class="btn primary" onclick="confirmPwd()">确认</button>
            </div>
          </div>
        </div>
        """.trimIndent()
        val script = """
        var upItems=[];
        var downData=null;
        var downChecked={};
        var downUnlocked=false;
        var pwdResolver=null;

        function switchCloudTab(w){
          document.getElementById('cloudUpload').style.display=(w==='upload')?'':'none';
          document.getElementById('cloudDownload').style.display=(w==='download')?'':'none';
          document.getElementById('ctabUp').classList.toggle('active',w==='upload');
          document.getElementById('ctabDown').classList.toggle('active',w==='download');
          if(w==='download'&&!downData){loadDownload();}
        }

        function askPwd(title){
          return new Promise(function(resolve){
            pwdResolver=resolve;
            document.getElementById('pwdTitle').textContent=title||'请输入密码';
            var inp=document.getElementById('pwdInput');inp.value='';
            document.getElementById('pwdModal').classList.remove('hidden');
            setTimeout(function(){inp.focus();},50);
          });
        }
        function confirmPwd(){var v=document.getElementById('pwdInput').value;document.getElementById('pwdModal').classList.add('hidden');var r=pwdResolver;pwdResolver=null;if(r){r(v);}}
        function hidePwd(){document.getElementById('pwdModal').classList.add('hidden');var r=pwdResolver;pwdResolver=null;if(r){r(null);}}

        // ---------------- 上传 ----------------
        function loadUpload(){
          var el=document.getElementById('upList');
          el.innerHTML='<div class="muted">正在加载本地合集…</div>';
          api.get('/api/cloud/upload_list').then(function(d){
            if(d&&d.status==='loading'){setTimeout(loadUpload,300);return;}
            upItems=(d.collections||[]).map(function(c){
              return {id:c.id,name:c.name,itemCount:c.itemCount||0,priv:!!c.private,type:c.type||'',
                checked:false,locked:!!c.locked,hadCloudHash:!!c.hadCloudHash,newPassword:null,
                isCreator:!!c.isCreator,canOperateLock:!!c.canOperateLock};
            });
            renderUpload();
          }).catch(function(e){el.innerHTML='<div class="empty"><span class="emoji">⚠️</span>加载失败，请重试 🔄<div class="muted" style="margin-top:6px">'+esc(e&&e.message||'')+'</div><button class="btn sm" style="margin-top:10px" onclick="loadUpload()">🔄 重试</button></div>';});
        }
        function renderUpload(){
          var el=document.getElementById('upList');
          if(!upItems.length){el.innerHTML='<div class="empty"><span class="emoji">🍡</span>暂无本地合集</div>';return;}
          el.innerHTML=upItems.map(function(it,idx){
            var typeText=it.priv?'private 私有':'shared 公开';
            var ownerText=it.isCreator?'我创建':'他人创建';
            var sub=it.itemCount+' 条 · '+typeText+' · '+ownerText+(it.locked?' · 🔒 本次加锁':'');
            var cb='<input type="checkbox" '+(it.checked?'checked':'')+(it.priv?' disabled':'')+' onclick="toggleUpCheck('+idx+',this)"/>';
            var lockBtn=(!it.priv&&it.canOperateLock)?'<button class="btn sm" onclick="toggleUpLock('+idx+')">'+(it.locked?'🔒':'🔓')+'</button>':'';
            var labelClick=it.priv?' onclick="toast(&quot;私有合集不支持上传到云端&quot;)"':'';
            var rowStyle=it.priv?'opacity:.5':'';
            return '<div class="item-row" style="'+rowStyle+'"><label style="display:flex;align-items:center;gap:8px;flex:1;min-width:0'+(it.priv?';cursor:not-allowed':'')+'"'+labelClick+'>'+cb+'<div class="grow"><div class="title">'+esc(it.name)+'</div><div class="subtitle">'+sub+'</div></div></label>'+lockBtn+'</div>';
          }).join('');
        }
        function toggleUpCheck(idx,el){if(upItems[idx].priv){el.checked=false;toast('私有合集不支持上传到云端');return;}upItems[idx].checked=el.checked;}
        function toggleUpLock(idx){
          var it=upItems[idx];
          if(!it.canOperateLock){toast('仅合集创建者可操作');return;}
          if(it.locked){it.locked=false;it.newPassword=null;toast('本次上传将取消该合集加密 🔓');renderUpload();return;}
          askPwd('设置本次上传密码').then(function(pw){
            if(pw==null||pw===''){return;}
            it.locked=true;it.newPassword=pw;toast('本次上传将加锁 🔒');renderUpload();
          });
        }
        function doUpload(){
          var sel=upItems.filter(function(it){return it.checked&&!it.priv;});
          if(!sel.length){toast('请先勾选要上传的合集');return;}
          var payload=sel.map(function(it){
            var action=it.locked?(it.newPassword?'set':'keep'):'clear';
            return {id:it.id,action:action,password:it.newPassword||''};
          });
          toast('正在上传，请稍候…',3000);
          api.post('/api/cloud/upload_selected',{items:payload}).then(function(r){
            toast(r.message||(r.success?'上传完成':'上传失败'),3000);loadUpload();
          }).catch(function(e){toast('上传失败：'+esc(e&&e.message||''),3000);});
        }

        // ---------------- 下载 ----------------
        function loadDownload(){
          var el=document.getElementById('downList');
          el.innerHTML='<div class="muted">正在加载云端合集…</div>';
          downChecked={};downUnlocked=false;
          api.get('/api/cloud/download_list').then(function(d){
            if(d&&d.status==='loading'){setTimeout(loadDownload,300);return;}
            if(!d.available){downData=null;document.getElementById('downHint').textContent='获取云端列表失败，请检查网络或 Gitee 配置';el.innerHTML='<div class="empty"><span class="emoji">☁️</span>'+esc(d.message||'获取云端列表失败')+'<button class="btn sm" style="margin-top:10px;display:block;margin-left:auto;margin-right:auto" onclick="loadDownload()">🔄 重试</button></div>';return;}
            downData=d;
            document.getElementById('downHint').innerHTML='预置合集，可直接勾选，进行下载<br/>自己创建的合集，可下载<br/>其他用户共享的加密合集，需解锁后勾选，进行下载';
            renderDownload();
          }).catch(function(e){el.innerHTML='<div class="empty"><span class="emoji">⚠️</span>加载失败，请重试 🔄<div class="muted" style="margin-top:6px">'+esc(e&&e.message||'')+'</div><button class="btn sm" style="margin-top:10px" onclick="loadDownload()">🔄 重试</button></div>';});
        }
        function downRow(it,isPreset){
          var enc=!!it.encrypted;
          var lockLabel=enc?((isPreset||downUnlocked)?'🔓':'🔒'):'';
          var cb='<input type="checkbox" '+(downChecked[it.id]?'checked':'')+' onclick="toggleDownCheck(\''+it.id+'\','+(isPreset?'true':'false')+','+(enc?'true':'false')+',this)"/>';
          var sub=(isPreset?'预置合集':'非预置')+' · '+esc(it.type||'')+(enc?((isPreset||downUnlocked)?' · 已解锁':' · 🔒 需密码'):'');
          return '<div class="item-row"><label style="display:flex;align-items:center;gap:8px;flex:1;min-width:0">'+cb+'<div class="grow"><div class="title">'+esc(it.name)+' '+lockLabel+'</div><div class="subtitle">'+sub+'</div></div></label></div>';
        }
        function renderDownload(){
          var el=document.getElementById('downList');
          if(!downData){return;}
          var pg=downData.presetGroup||[],ng=downData.nonPresetGroup||[];
          var html='';
          if(pg.length){html+='<div class="muted" style="margin:6px 0;font-weight:700;color:var(--o)">【预置合集】可直接勾选，无需解锁</div>';html+=pg.map(function(it){return downRow(it,true);}).join('');}
          if(ng.length){html+='<div class="muted" style="margin:12px 0 6px;font-weight:700;color:var(--y)">【非预置合集】最多选择 '+downData.maxNonPresetDownload+' 个；加密合集点击输入一次密码后本组均可勾选</div>';html+=ng.map(function(it){return downRow(it,false);}).join('');}
          if(!pg.length&&!ng.length){html='<div class="empty"><span class="emoji">☁️</span>云端暂无可下载合集</div>';}
          el.innerHTML=html;
        }
        function nonPresetIdSet(){var s={};(downData.nonPresetGroup||[]).forEach(function(it){s[it.id]=1;});return s;}
        function selectedTotal(){return Object.keys(downChecked).filter(function(k){return downChecked[k];}).length;}
        function selectedNonPreset(){var s=nonPresetIdSet();return Object.keys(downChecked).filter(function(k){return downChecked[k]&&s[k];}).length;}
        function toggleDownCheck(id,isPreset,encrypted,el){
          if(!el.checked){downChecked[id]=false;return;}
          // 加密的非预置项：本页未解锁时先弹一次密码验证
          if(!isPreset&&encrypted&&!downUnlocked){
            el.checked=false;
            askPwd('该合集已加密，请输入密码').then(function(pw){
              if(pw==null||pw===''){return;}
              function verifyPwd(at){
                api.post('/api/cloud/verify_password',{id:id,password:pw}).then(function(r){
                  if(r&&r.status==='loading'){
                    if(at<10){setTimeout(function(){verifyPwd(at+1);},300);return;}
                    toast('校验超时，请重试');
                    return;
                  }
                  if(r&&r.matched){
                    downUnlocked=true;toast('✅ 已解锁本页全部加密合集');
                    tryCheck(id,isPreset);renderDownload();
                  }else{toast('❌ 密码错误');}
                }).catch(function(e){toast('校验失败：'+esc(e&&e.message||''));});
              }
              verifyPwd(0);
            });
            return;
          }
          if(!tryCheck(id,isPreset)){el.checked=false;}
        }
        function tryCheck(id,isPreset){
          var maxTotal=downData.maxTotalDownload,maxNonPreset=downData.maxNonPresetDownload;
          if(selectedTotal()>=maxTotal){toast('已达下载上限（'+selectedTotal()+'/'+maxTotal+'）');return false;}
          if(!isPreset&&selectedNonPreset()>=maxNonPreset){toast('非预置合集最多选 '+maxNonPreset+' 个');return false;}
          downChecked[id]=true;return true;
        }
        function doDownload(){
          var ids=Object.keys(downChecked).filter(function(k){return downChecked[k];});
          if(!ids.length){toast('请先勾选要下载的合集');return;}
          toast('正在下载，请稍候…',3000);
          api.post('/api/cloud/download_selected',{ids:ids}).then(function(r){
            toast(r.message||(r.success?'下载完成':'下载失败'),3000);
          }).catch(function(e){toast('下载失败：'+esc(e&&e.message||''),3000);});
        }

        loadUpload();
        """.trimIndent()
        return frame("cloud", "云同步", body, script)
    }

    // ======================================== FAVORITES
    fun favorites(): String {
        val body = """
        <div class="col-toolbar">
          <button class="btn primary" onclick="showAddCol()">＋ 新建合集</button>
          <a class="btn success" href="/export.csv">⬇︎ 导出 CSV</a>
          <a class="btn success" href="/export.json">⬇︎ 导出 JSON</a>
          <button class="btn" onclick="document.getElementById('csvFile').click()">⬆︎ 导入 CSV</button>
          <button class="btn sm" onclick="load()">🔄 刷新</button>
          <input id="csvFile" type="file" accept=".csv,text/csv" style="display:none" onchange="uploadCsv(this)"/>
        </div>
        <div id="list"></div>

        <div id="modalAddCol" class="modal-mask hidden" onclick="if(event.target===this)hideAddCol()">
          <div class="modal">
            <h3>新建合集</h3>
            <input id="newColName" type="text" placeholder="合集名称（1-14 字）" maxlength="14"/>
            <div style="margin-top:14px;text-align:right">
              <button class="btn" onclick="hideAddCol()">取消</button>
              <button class="btn primary" onclick="doAddCol()">确认</button>
            </div>
          </div>
        </div>
        """
        val script = """
        let favoriteCollections = [];
        let favoriteDetails = {};
        let queuedUris = new Set();

        function showAddCol(){document.getElementById('modalAddCol').classList.remove('hidden');document.getElementById('newColName').focus()}
        function hideAddCol(){document.getElementById('modalAddCol').classList.add('hidden')}
        function clearFavoriteCache(ids){if(!Array.isArray(ids)||!ids.length){favoriteDetails={};return;}ids.forEach(id=>delete favoriteDetails[id]);}
        function renderCollectionItems(cid){
          const host=document.getElementById('collection-items-'+encodeURIComponent(cid));
          if(!host)return;
          const detail=favoriteDetails[cid];
          if(!detail){host.innerHTML='<div class="empty" style="padding:14px 8px"><span class="emoji">⏳</span>正在加载合集内容…</div>';return;}
          const items=detail.items||[];
          if(!items.length){host.innerHTML='<div class="empty" style="padding:14px 8px"><span class="emoji">📭</span>合集为空</div>';return;}
          host.innerHTML=items.map(it=>{
            const title=encodeURIComponent(it.title||it.uri||'');
            const uri=encodeURIComponent(it.uri||'');
            const inQueue=queuedUris.has(it.uri||'');
            const qCls=inQueue?'btn danger sm':'btn primary sm';
            const qText=inQueue?'移出队列':'＋ 稍后观看';
            return `<div class="item-row favorite-item-row"><div class="grow"><div class="title">${'$'}{esc(it.title||it.uri)}</div><div class="subtitle">${'$'}{esc(it.uri)}</div></div><div class="item-actions favorite-item-actions"><button class="btn primary sm" onclick="playItem(decodeURIComponent(this.dataset.title),decodeURIComponent(this.dataset.uri))" data-title="${'$'}{title}" data-uri="${'$'}{uri}">▶</button><button class="${'$'}{qCls}" onclick="toggleQueue(decodeURIComponent(this.dataset.title),decodeURIComponent(this.dataset.uri),'favorite')" data-title="${'$'}{title}" data-uri="${'$'}{uri}">${'$'}{qText}</button><button class="btn sm" onclick="moveItem('${'$'}{cid}',decodeURIComponent(this.dataset.uri))" data-uri="${'$'}{uri}">⇄</button><button class="btn sm" onclick="renameItem('${'$'}{cid}',decodeURIComponent(this.dataset.uri),decodeURIComponent(this.dataset.title))" data-title="${'$'}{title}" data-uri="${'$'}{uri}">✎</button><button class="btn danger sm" onclick="delItem('${'$'}{cid}',decodeURIComponent(this.dataset.uri))" data-uri="${'$'}{uri}">✕</button></div></div>`;
          }).join('');
        }
        async function ensureCollectionLoaded(cid){
          if(favoriteDetails[cid]){renderCollectionItems(cid);return favoriteDetails[cid];}
          const r=await api.get('/api/favorites/collection?id='+encodeURIComponent(cid));
          if(!r.ok||!r.collection){throw new Error(r.error||'合集加载失败');}
          favoriteDetails[cid]=r.collection;
          renderCollectionItems(cid);
          return r.collection;
        }
        async function doAddCol(){const n=document.getElementById('newColName').value.trim();if(!n){toast('合集名不能为空');return}const r=await api.post('/api/favorites/add_collection',{name:n});toast(r.id?'已新建':'新建失败');hideAddCol();document.getElementById('newColName').value='';clearFavoriteCache();load()}
        async function delCol(id){if(!confirm('删除该合集及其内含全部收藏？'))return;const r=await api.post('/api/favorites/delete_collection',{id});toast(r.deleted?'已删除':'删除失败');clearFavoriteCache([id]);load()}
        async function renameCol(id,curName){const n=prompt('修改合集名称',curName);if(!n||n.trim()===''||n.trim()===curName)return;const r=await api.post('/api/favorites/rename_collection',{id,name:n.trim()});toast(r.renamed?'已保存':'保存失败');clearFavoriteCache([id]);load()}
        async function delItem(cid,uri){if(!confirm('删除该收藏？'))return;const r=await api.post('/api/favorites/delete_item',{collectionId:cid,uri});toast(r.deleted?'已删除':'删除失败');clearFavoriteCache([cid]);load()}
        async function renameItem(cid,uri,curTitle){const t=prompt('修改标题',curTitle);if(!t||t===curTitle)return;const r=await api.post('/api/favorites/rename_item',{collectionId:cid,uri,title:t.trim()});toast(r.renamed?'已保存':'保存失败');clearFavoriteCache([cid]);load()}
        async function moveItem(fromId,uri){
          const targets=favoriteCollections.filter(c=>c.id!==fromId);
          if(!targets.length){toast('没有可移动的目标合集');return;}
          const guide=targets.map((c,i)=>`${'$'}{i+1}. ${'$'}{c.name}`).join('\n');
          const picked=prompt(`移动到哪个合集？\n${'$'}{guide}`,'1');
          if(picked===null)return;
          const idx=Number(picked)-1;
          if(!Number.isInteger(idx)||idx<0||idx>=targets.length){toast('请输入正确的序号');return;}
          const target=targets[idx];
          const r=await api.post('/api/favorites/move_item',{fromCollectionId:fromId,toCollectionId:target.id,uri});
          toast(r.moved?`已移动到「${'$'}{target.name}」`:'移动失败');
          clearFavoriteCache([fromId,target.id]);
          load();
        }
        async function toggleQueue(title,uri,source){
          const q=await api.get('/api/queue');
          const hit=(q.items||[]).find(x=>x.uri===uri);
          if(hit){const r=await api.post('/api/queue/remove',{id:hit.id});toast(r.removed?'✅ 已移出队列':'⚠️ 移出失败')}
          else{const r=await api.post('/api/queue/add',{title,uri,source:source||'favorite'});toast(r.id?'✅ 已加入稍后播放':'⚠️ 失败')}
          load();
        }
        async function uploadCsv(inp){const f=inp.files[0];if(!f)return;const fd=new FormData();fd.append('csv',f);const r=await fetch('/import.csv',{method:'POST',body:fd}).then(r=>r.json());toast(r.message||(r.valid?'导入完成':'导入失败'),3000);inp.value='';clearFavoriteCache();load()}
        async function load(){
          const el=document.getElementById('list');
          try{
          const [data,queue]=await Promise.all([api.get('/api/favorites'),api.get('/api/queue')]);
          queuedUris=new Set((queue.items||[]).map(x=>x.uri));
          favoriteCollections=data.collections||[];
          if(!favoriteCollections.length){el.innerHTML='<div class="empty"><span class="emoji">🍡</span>还没有合集，点击「＋ 新建合集」开始</div>';return}
          el.innerHTML=favoriteCollections.map(c=>{
            const tag=(c.isDefault?'<span class="tag default">默认</span>':'')+(c.isPreset?'<span class="tag preset">预置</span>':'');
            const encodedName=encodeURIComponent(c.name||'');
            const encodedId=encodeURIComponent(c.id||'');
            const canEdit=!c.isDefault && !c.isPreset;
            const canDelete=!c.isDefault && !c.isPreset;
            const editBtn=canEdit?`<button class="btn sm" data-id="${'$'}{encodedId}" data-name="${'$'}{encodedName}" onclick="event.stopPropagation();renameCol(decodeURIComponent(this.dataset.id),decodeURIComponent(this.dataset.name))">✎</button>`:'';
            const deleteBtn=canDelete?`<button class="btn danger sm" data-id="${'$'}{encodedId}" onclick="event.stopPropagation();delCol(decodeURIComponent(this.dataset.id))">✕</button>`:'';
            const headActions=`<div class="item-actions">${'$'}{editBtn}${'$'}{deleteBtn}</div>`;
            return `<details data-cid="${'$'}{encodedId}" ${'$'}{c.isDefault?'open':''} ontoggle="if(this.open)ensureCollectionLoaded(decodeURIComponent(this.dataset.cid)).catch(err=>toast(err.message||'加载失败'))"><summary><span>📚 ${'$'}{esc(c.name)} ${'$'}{tag} <span class="muted" style="font-weight:400">（${'$'}{c.itemCount||0}）</span></span>${'$'}{headActions}</summary><div id="collection-items-${'$'}{encodedId}"><div class="empty" style="padding:14px 8px"><span class="emoji">📦</span>展开后加载合集内容</div></div></details>`;
          }).join('');
          favoriteCollections.filter(c=>c.isDefault).forEach(c=>ensureCollectionLoaded(c.id).catch(err=>toast(err.message||'加载失败')));
          }catch(e){el.innerHTML='<div class="empty"><span class="emoji">⚠️</span>加载失败，请重试 🔄<div class="muted" style="margin-top:6px">'+esc(e&&e.message||'')+'</div><button class="btn sm" style="margin-top:10px" onclick="load()">🔄 重试</button></div>';}
        }
        load();
        """
        return frame("favorites", "我的收藏", body, script)
    }

    // ======================================== HISTORY
    fun history(): String {
        val body = """
        <div class="col-toolbar">
          <button class="btn danger" onclick="clearAll()">🗑 清空全部</button>
          <button class="btn sm" onclick="load()">🔄 刷新</button>
        </div>
        <div id="list"></div>
        """
        val script = """
        async function delOne(uri){if(!confirm('删除这条历史？'))return;const r=await api.post('/api/history/remove',{uri});toast(r.removed?'已删除':'删除失败');load()}
        async function clearAll(){if(!confirm('确认清空所有历史？'))return;const r=await api.post('/api/history/clear',{});toast('已清空');load()}
        async function toggleQueue(title,uri,source){
          const q=await api.get('/api/queue');
          const hit=(q.items||[]).find(x=>x.uri===uri);
          if(hit){const r=await api.post('/api/queue/remove',{id:hit.id});toast(r.removed?'✅ 已移出队列':'⚠️ 移出失败')}
          else{const r=await api.post('/api/queue/add',{title,uri,source:source||'history'});toast(r.id?'✅ 已加入稍后播放':'⚠️ 失败')}
          load();
        }
        async function load(){
          const el=document.getElementById('list');
          try{
          const [data,queue]=await Promise.all([api.get('/api/history'),api.get('/api/queue')]);const queued=new Set((queue.items||[]).map(x=>x.uri));const items=data.items||[];
          if(!items.length){el.innerHTML='<div class="empty"><span class="emoji">🕒</span>暂无历史记录</div>';return}
          el.innerHTML=items.map(it=>{const inQueue=queued.has(it.uri||'');const qCls=inQueue?'btn danger sm':'btn primary sm';const qText=inQueue?'移出队列':'＋ 稍后观看';return `<div class="item-row"><div class="grow"><div class="title">${'$'}{esc(it.title||it.uri)}</div><div class="subtitle">${'$'}{esc(it.source||'')} · ${'$'}{fmtTime(it.time)}</div></div><div class="item-actions"><button class="btn primary sm" onclick="playItem(${'$'}{JSON.stringify(it.title||it.uri)},${'$'}{JSON.stringify(it.uri)})">▶</button><button class="${'$'}{qCls}" onclick="toggleQueue(${'$'}{JSON.stringify(it.title||it.uri)},${'$'}{JSON.stringify(it.uri)},'history')">${'$'}{qText}</button><button class="btn danger sm" onclick="delOne(${'$'}{JSON.stringify(it.uri)})">✕</button></div></div>`}).join('');
          }catch(e){el.innerHTML='<div class="empty"><span class="emoji">⚠️</span>加载失败，请重试 🔄<div class="muted" style="margin-top:6px">'+esc(e&&e.message||'')+'</div><button class="btn sm" style="margin-top:10px" onclick="load()">🔄 重试</button></div>';}
        }
        load();
        """
        return frame("history", "历史记录", body, script)
    }

    // ======================================== QUEUE
    fun queue(): String {
        val body = """
        <div class="card">
          <h3>📼 播放队列</h3>
          <div class="muted">状态说明：<span class="tag pending">待播放</span><span class="tag playing">播放中</span><span class="tag finished">播放结束</span></div>
          <div class="col-toolbar" style="margin-top:12px">
            <button class="btn primary" onclick="push('override')">📺 覆盖队列并播放</button>
            <button class="btn success" onclick="push('append')">➕ 追加到电视队列末尾</button>
            <button class="btn danger" onclick="clearAll()">🗑 清空</button>
            <button class="btn sm" onclick="load()">🔄 刷新</button>
          </div>
        </div>
        <div id="list"></div>
        """
        val script = """
        let dragSrc=null;let items=[];
        function statusTag(s){return s==='PLAYING'?'<span class="tag playing">播放中</span>':s==='FINISHED'?'<span class="tag finished">播放结束</span>':'<span class="tag pending">待播放</span>'}
        function render(){
          const el=document.getElementById('list');
          if(!items.length){el.innerHTML='<div class="empty"><span class="emoji">📼</span>队列为空，去收藏或历史里「＋ 稍后播放」吧</div>';return}
          el.innerHTML=items.map((it,idx)=>`<div class="item-row ${'$'}{it.status==='PLAYING'?'playing':''}" draggable="true" data-id="${'$'}{it.id}"><div class="drag-handle" ontouchstart="event.stopPropagation()">⋮⋮</div><div class="grow"><div class="title">${'$'}{esc(it.title||it.uri)} ${'$'}{statusTag(it.status)}</div><div class="subtitle">#${'$'}{idx+1} · ${'$'}{esc(it.source||'')} · ${'$'}{fmtTime(it.addedAt)}</div></div><div class="item-actions"><button class="btn sm" onclick=\"moveUp('${'$'}{it.id}')\">⬆︎</button><button class="btn sm" onclick=\"moveDown('${'$'}{it.id}')\">⬇︎</button><button class="btn danger sm" onclick=\"del('${'$'}{it.id}')\">✕</button></div></div>`).join('');
          // 拖动排序
          el.querySelectorAll('.item-row').forEach(r=>{
            r.addEventListener('dragstart',e=>{dragSrc=r;r.classList.add('dragging');e.dataTransfer.effectAllowed='move'});
            r.addEventListener('dragend',_=>{r.classList.remove('dragging');dragSrc=null});
            r.addEventListener('dragover',e=>{e.preventDefault();if(dragSrc&&dragSrc!==r){const b=r.getBoundingClientRect();if(e.clientY-b.top<b.height/2)el.insertBefore(dragSrc,r);else el.insertBefore(dragSrc,r.nextSibling)}});
            r.addEventListener('drop',_=>{persistOrder()});
          });
        }
        async function persistOrder(){const ids=[...document.querySelectorAll('.item-row')].map(r=>r.dataset.id);await api.post('/api/queue/reorder',{ids});toast('顺序已保存');load()}
        function moveUp(id){const i=items.findIndex(x=>x.id===id);if(i<=0)return;const t=items[i-1];items[i-1]=items[i];items[i]=t;api.post('/api/queue/reorder',{ids:items.map(x=>x.id)}).then(()=>{toast('已上移');load()})}
        function moveDown(id){const i=items.findIndex(x=>x.id===id);if(i<0||i>=items.length-1)return;const t=items[i+1];items[i+1]=items[i];items[i]=t;api.post('/api/queue/reorder',{ids:items.map(x=>x.id)}).then(()=>{toast('已下移');load()})}
        async function del(id){if(!confirm('从队列移除该条？'))return;const r=await api.post('/api/queue/remove',{id});toast(r.removed?'已移除':'移除失败');load()}
        async function clearAll(){if(!confirm('清空整个播放队列？'))return;await api.post('/api/queue/clear',{});toast('已清空');load()}
        async function push(mode){
          if(!items.length){toast('队列为空，先加入内容');return}
          if(mode==='override'&&!confirm('覆盖当前电视队列并从第一条开始播放？'))return;
          const r=await api.post('/api/queue/push',{mode,triggerPlayFirst:true});
          toast(mode==='override'?'📺 已推送到电视并开始播放':'➕ 已追加到电视队列末尾',2400);
        }
        async function load(){
          try{const d=await api.get('/api/queue');items=d.items||[];render();}
          catch(e){const el=document.getElementById('list');if(el)el.innerHTML='<div class="empty"><span class="emoji">⚠️</span>加载失败，请重试 🔄<div class="muted" style="margin-top:6px">'+esc(e&&e.message||'')+'</div><button class="btn sm" style="margin-top:10px" onclick="load()">🔄 重试</button></div>';}
        }
        load();window.__tabTimer=setInterval(load,4000);
        """
        return frame("queue", "稍后播放", body, script)
    }

    // ======================================== SETTINGS
    fun settings(): String {
        val body = """
        <div class="banner" style="background:rgba(232,69,60,.1);border-color:rgba(232,69,60,.5);color:#F2913D">
          ⚠️ 部分设置修改后需要 <b>重启接收服务</b> 才能生效，标有 <span class="tag preset">需重启</span> 的项将会在电视上弹出提示。
        </div>
        <div id="list"></div>
        """
        val script = """
        async function update(key,value){
          const r=await api.post('/api/settings/update',{key,value:String(value)});
          toast(r.needRestart?'✅ 已保存 · TV 已弹重启提示':'✅ 已保存',2000);
        }
        async function load(){
          const d=await api.get('/api/settings');const items=d.items||[];const el=document.getElementById('list');
          el.innerHTML=items.map(it=>{
            const tag=it.needRestart?'<span class="tag preset">需重启</span>':'<span class="tag pending">即时</span>';
            let ctrl='';
            if(it.type==='toggle'){
              const on=(it.value==='on'||it.value==='true');
              ctrl=`<label class="switch"><input type="checkbox" ${'$'}{on?'checked':''} onchange=\"update('${'$'}{it.key}',this.checked?'on':'off')\"/><span class="slider"></span></label>`;
            }else if(it.type==='quality'){
              const v=it.value||'auto';const opts=['auto','1080','4k'];
              ctrl=`<select onchange=\"update('${'$'}{it.key}',this.value)\">${'$'}{opts.map(o=>`<option value=\"${'$'}{o}\" ${'$'}{v===o?'selected':''}>${'$'}{o}</option>`).join('')}</select>`;
            }else if(it.type==='screensaver'){
              const v=it.value||'track';const opts=[['track','横向轨道流（默认）'],['waterfall','多列瀑布流'],['mosaic','随机砖块网格']];
              ctrl=`<select onchange=\"update('${'$'}{it.key}',this.value)\">${'$'}{opts.map(([o,label])=>`<option value=\"${'$'}{o}\" ${'$'}{v===o?'selected':''}>${'$'}{label}</option>`).join('')}</select>`;
            }else if(it.type==='password'){
              ctrl=`<div class=\"row\"><input class=\"grow\" type=\"password\" placeholder=\"4-8 位数字\" value=\"${'$'}{esc(it.value||'')}\" id=\"in_${'$'}{it.key}\"/><button class=\"btn primary sm\" onclick=\"update('${'$'}{it.key}',document.getElementById('in_${'$'}{it.key}').value)\">保存</button></div>`;
            }else{
              ctrl=`<div class=\"row\"><input class=\"grow\" type=\"text\" value=\"${'$'}{esc(it.value||'')}\" id=\"in_${'$'}{it.key}\"/><button class=\"btn primary sm\" onclick=\"update('${'$'}{it.key}',document.getElementById('in_${'$'}{it.key}').value)\">保存</button></div>`;
            }
            return `<div class=\"card\"><h3>${'$'}{esc(it.label)} ${'$'}{tag}</h3><div class=\"muted\" style=\"margin-bottom:10px\">${'$'}{esc(it.desc||'')}</div>${'$'}{ctrl}</div>`;
          }).join('');
        }
        load();
        """
        return frame("settings", "设置", body, script)
    }

    // ======================================== WALLPAPER UPLOAD
    fun wallpaper(): String {
        val body = """
        <div class="banner"><span class="pin">🖼</span>从手机上传壁纸图片到电视端，上传后可在电视端壁纸设置中应用。最多 13 张自定义壁纸。</div>
        <div class="card">
          <h3>🖼 壁纸上传</h3>
          <div id="wpStatus" class="muted" style="margin-bottom:10px">正在加载…</div>
          <div style="margin-bottom:12px">
            <input id="wpFile" type="file" accept="image/jpeg,image/png,.jpg,.jpeg,.png" style="display:none" onchange="previewFile(this)"/>
            <button class="btn primary" onclick="document.getElementById('wpFile').click()">📁 选择图片</button>
            <button class="btn sm" onclick="loadStatus()">🔄 刷新</button>
          </div>
          <div id="wpPreview" style="display:none;margin-bottom:12px">
            <img id="wpPreviewImg" style="max-width:100%;max-height:240px;border-radius:12px;border:1.5px solid var(--stroke)"/>
          </div>
          <div id="wpUploadArea" style="display:none">
            <button class="btn primary" onclick="doUpload()">⬆️ 上传到电视</button>
            <span id="wpProgress" class="muted" style="margin-left:10px"></span>
          </div>
          <div id="wpResult" style="margin-top:12px"></div>
        </div>
        <div class="card">
          <h3>📋 已有自定义壁纸</h3>
          <div id="wpList"><div class="muted">加载中…</div></div>
        </div>
        """
        val script = """
        var wpSelectedFile = null;

        function previewFile(inp) {
          var f = inp.files[0];
          if (!f) return;
          wpSelectedFile = f;
          var reader = new FileReader();
          reader.onload = function(e) {
            document.getElementById('wpPreviewImg').src = e.target.result;
            document.getElementById('wpPreview').style.display = '';
            document.getElementById('wpUploadArea').style.display = '';
            document.getElementById('wpResult').innerHTML = '';
          };
          reader.readAsDataURL(f);
        }

        function doUpload() {
          if (!wpSelectedFile) { toast('请先选择图片'); return; }
          var fd = new FormData();
          fd.append('wallpaper', wpSelectedFile);
          document.getElementById('wpProgress').textContent = '正在上传…';
          fetch('/api/wallpaper/upload', { method: 'POST', body: fd })
            .then(function(r) { return r.json(); })
            .then(function(d) {
              if (d.ok) {
                document.getElementById('wpResult').innerHTML = '<div class="muted" style="color:var(--g)">✅ ' + esc(d.message || '上传成功') + '</div>';
                toast('上传成功');
                wpSelectedFile = null;
                document.getElementById('wpFile').value = '';
                document.getElementById('wpPreview').style.display = 'none';
                document.getElementById('wpUploadArea').style.display = 'none';
                loadStatus();
              } else {
                document.getElementById('wpResult').innerHTML = '<div class="muted" style="color:var(--r)">❌ ' + esc(d.error || '上传失败') + '</div>';
                toast(d.error || '上传失败');
              }
              document.getElementById('wpProgress').textContent = '';
            })
            .catch(function(e) {
              document.getElementById('wpProgress').textContent = '';
              document.getElementById('wpResult').innerHTML = '<div class="muted" style="color:var(--r)">❌ 网络错误</div>';
              toast('上传失败');
            });
        }

        function loadStatus() {
          api.get('/api/wallpaper/status').then(function(d) {
            var count = d.count || 0;
            var max = d.max || 13;
            document.getElementById('wpStatus').innerHTML = '已有 <b>' + count + '</b> / ' + max + ' 张自定义壁纸';
            var list = d.files || [];
            var el = document.getElementById('wpList');
            if (!list.length) {
              el.innerHTML = '<div class="empty"><span class="emoji">🖼</span>暂无自定义壁纸</div>';
              return;
            }
            el.innerHTML = list.map(function(name) {
              return '<div class="item-row"><div class="grow"><div class="title">' + esc(name) + '</div></div><button class="btn danger sm" onclick="delWallpaper(\'' + esc(name) + '\')">删除</button></div>';
            }).join('');
          }).catch(function(e) {
            document.getElementById('wpStatus').textContent = '加载失败';
            document.getElementById('wpList').innerHTML = '<div class="empty"><span class="emoji">⚠️</span>加载失败<button class="btn sm" style="margin-top:10px" onclick="loadStatus()">🔄 重试</button></div>';
          });
        }

        function delWallpaper(name) {
          if (!confirm('确认删除壁纸 ' + name + '？')) return;
          api.post('/api/wallpaper/delete', { name: name }).then(function(d) {
            toast(d.ok ? '已删除' : (d.error || '删除失败'));
            loadStatus();
          }).catch(function() { toast('删除失败'); });
        }

        loadStatus();
        """
        return frame("wallpaper", "壁纸上传", body, script)
    }
}
