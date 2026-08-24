
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
            var lockBtn=it.priv?'':'<button class="btn sm" onclick="toggleUpLock('+idx+')">'+(it.locked?'🔒':'🔓')+'</button>';
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
            document.getElementById('downHint').innerHTML='预置合集可直接勾选；非预置加密合集点击后输入一次密码即可解锁本页全部；<b>总下载数最多 '+d.maxTotalDownload+' 个，非预置最多 '+d.maxNonPresetDownload+' 个</b>';
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
        