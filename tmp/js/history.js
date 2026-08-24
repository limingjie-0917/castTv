
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
        