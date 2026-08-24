
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

        async function loadCloud(){
          const box=document.getElementById('cloudStatus');
          box.innerHTML='<div class="muted">正在读取云同步状态...</div>';
          try{
            const d=await api.get('/api/cloud/status');
            const list=d.cloudCollections||[];
            box.innerHTML='<h3>同步状态</h3>'+
              '<div class="muted">本地合集：'+d.localCount+' 个，共 '+d.localItemCount+' 条；云端合集：'+(d.cloudAvailable?d.cloudCount+' 个':'读取失败')+'</div>'+
              (list.length?'<div style="margin-top:12px">'+list.map(c=>'<div class="item"><div><b>'+esc(c.name||c.id)+'</b><div class="muted">类型：'+esc(c.type||'')+' · 更新时间：'+fmtTime(c.updatedAt)+'</div></div></div>').join('')+'</div>':'<div class="muted" style="margin-top:12px">暂无云端合集列表</div>');
          }catch(e){box.innerHTML='<h3>同步状态</h3><div class="muted">加载失败，请重试 🔄</div><div class="muted" style="margin-top:6px">'+esc(e&&e.message||'')+'</div><button class="btn sm" style="margin-top:10px" onclick="loadCloud()">🔄 重试</button>';}
        }
        async function syncUpload(){
          if(!confirm('确认将电视端当前收藏上传到云端？'))return;
          toast('正在上传，请稍候...',3000);
          const r=await api.post('/api/cloud/upload',{});
          toast(r.message||(r.success?'上传完成':'上传失败'),3000);
          loadCloud();
        }
        async function syncDownload(){
          if(!confirm('确认从云端下载收藏并覆盖同 ID 本地合集？'))return;
          toast('正在下载，请稍候...',3000);
          const r=await api.post('/api/cloud/download',{});
          toast(r.message||(r.success?'下载完成':'下载失败'),3000);
          loadCloud();
        }
        loadCloud();
        