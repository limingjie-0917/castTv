
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
        