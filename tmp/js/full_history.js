
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
            async function loadTab(url,tab){
              const needsLoading=['favorites','history','cloud','queue'].includes(tab);const loader=document.getElementById('tabLoading');
              if(needsLoading){loader.textContent='正在加载'+({'favorites':'收藏','history':'历史记录','cloud':'云同步','queue':'稍后播放'}[tab]||'当前栏目')+'…';loader.classList.add('show');}
              try{
                if(window.__tabTimer){clearInterval(window.__tabTimer);window.__tabTimer=null;}
                const resp=await fetch(url,{headers:{'X-Requested-With':'fetch'}});
                if(!resp.ok){throw new Error('HTTP '+resp.status);}
                const html=await resp.text();
                const doc=new DOMParser().parseFromString(html,'text/html');
                const next=doc.querySelector('.container');const cur=document.querySelector('.container');
                if(next&&cur)cur.innerHTML=next.innerHTML;
                document.querySelectorAll('.nav a').forEach(a=>a.classList.toggle('active',a.dataset.tab===tab));
                history.pushState({tab,url},'',url);document.title=(doc.querySelector('title')||{}).textContent||document.title;
                const scripts=[...doc.querySelectorAll('script')];const pageScript=scripts[scripts.length-1];
                if(pageScript){const marker='//__PAGE_SCRIPT_START__

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
          el.innerHTML=items.map(it=>{const inQueue=queued.has(it.uri||'');const qCls=inQueue?'btn danger sm':'btn primary sm';const qText=inQueue?'移出队列':'＋ 稍后观看';return `<div class="item-row"><div class="grow"><div class="title">${esc(it.title||it.uri)}</div><div class="subtitle">${esc(it.source||'')} · ${fmtTime(it.time)}</div></div><div class="item-actions"><button class="${qCls}" onclick="toggleQueue(${JSON.stringify(it.title||it.uri)},${JSON.stringify(it.uri)},'history')">${qText}</button><button class="btn danger sm" onclick="delOne(${JSON.stringify(it.uri)})">✕</button></div></div>`}).join('');
          }catch(e){el.innerHTML='<div class="empty"><span class="emoji">⚠️</span>加载失败，请重试 🔄<div class="muted" style="margin-top:6px">'+esc(e&&e.message||'')+'</div><button class="btn sm" style="margin-top:10px" onclick="load()">🔄 重试</button></div>';}
        }
        load();
        