
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

        let dragSrc=null;let items=[];
        function statusTag(s){return s==='PLAYING'?'<span class="tag playing">播放中</span>':s==='FINISHED'?'<span class="tag finished">播放结束</span>':'<span class="tag pending">待播放</span>'}
        function render(){
          const el=document.getElementById('list');
          if(!items.length){el.innerHTML='<div class="empty"><span class="emoji">📼</span>队列为空，去收藏或历史里「＋ 稍后播放」吧</div>';return}
          el.innerHTML=items.map((it,idx)=>`<div class="item-row ${it.status==='PLAYING'?'playing':''}" draggable="true" data-id="${it.id}"><div class="drag-handle" ontouchstart="event.stopPropagation()">⋮⋮</div><div class="grow"><div class="title">${esc(it.title||it.uri)} ${statusTag(it.status)}</div><div class="subtitle">#${idx+1} · ${esc(it.source||'')} · ${fmtTime(it.addedAt)}</div></div><div class="item-actions"><button class="btn sm" onclick=\"moveUp('${it.id}')\">⬆︎</button><button class="btn sm" onclick=\"moveDown('${it.id}')\">⬇︎</button><button class="btn danger sm" onclick=\"del('${it.id}')\">✕</button></div></div>`).join('');
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
        