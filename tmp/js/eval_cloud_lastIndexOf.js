
            
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
        
          