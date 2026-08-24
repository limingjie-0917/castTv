
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
            return `<div class="item-row"><div class="grow"><div class="title">${esc(it.title||it.uri)}</div><div class="subtitle">${esc(it.uri)}</div></div><div class="item-actions"><button class="${qCls}" onclick="toggleQueue(decodeURIComponent(this.dataset.title),decodeURIComponent(this.dataset.uri),'favorite')" data-title="${title}" data-uri="${uri}">${qText}</button><button class="btn sm" onclick="moveItem('${cid}',decodeURIComponent(this.dataset.uri))" data-uri="${uri}">⇄</button><button class="btn sm" onclick="renameItem('${cid}',decodeURIComponent(this.dataset.uri),decodeURIComponent(this.dataset.title))" data-title="${title}" data-uri="${uri}">✎</button><button class="btn danger sm" onclick="delItem('${cid}',decodeURIComponent(this.dataset.uri))" data-uri="${uri}">✕</button></div></div>`;
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
          const guide=targets.map((c,i)=>`${i+1}. ${c.name}`).join('\n');
          const picked=prompt(`移动到哪个合集？\n${guide}`,'1');
          if(picked===null)return;
          const idx=Number(picked)-1;
          if(!Number.isInteger(idx)||idx<0||idx>=targets.length){toast('请输入正确的序号');return;}
          const target=targets[idx];
          const r=await api.post('/api/favorites/move_item',{fromCollectionId:fromId,toCollectionId:target.id,uri});
          toast(r.moved?`已移动到「${target.name}」`:'移动失败');
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
            const editBtn=canEdit?`<button class="btn sm" data-id="${encodedId}" data-name="${encodedName}" onclick="event.stopPropagation();renameCol(decodeURIComponent(this.dataset.id),decodeURIComponent(this.dataset.name))">✎</button>`:'';
            const deleteBtn=canDelete?`<button class="btn danger sm" data-id="${encodedId}" onclick="event.stopPropagation();delCol(decodeURIComponent(this.dataset.id))">✕</button>`:'';
            const headActions=`<div class="item-actions">${editBtn}${deleteBtn}</div>`;
            return `<details data-cid="${encodedId}" ${c.isDefault?'open':''} ontoggle="if(this.open)ensureCollectionLoaded(decodeURIComponent(this.dataset.cid)).catch(err=>toast(err.message||'加载失败'))"><summary><span>📚 ${esc(c.name)} ${tag} <span class="muted" style="font-weight:400">（${c.itemCount||0}）</span></span>${headActions}</summary><div id="collection-items-${encodedId}"><div class="empty" style="padding:14px 8px"><span class="emoji">📦</span>展开后加载合集内容</div></div></details>`;
          }).join('');
          favoriteCollections.filter(c=>c.isDefault).forEach(c=>ensureCollectionLoaded(c.id).catch(err=>toast(err.message||'加载失败')));
          }catch(e){el.innerHTML='<div class="empty"><span class="emoji">⚠️</span>加载失败，请重试 🔄<div class="muted" style="margin-top:6px">'+esc(e&&e.message||'')+'</div><button class="btn sm" style="margin-top:10px" onclick="load()">🔄 重试</button></div>';}
        }
        load();
        