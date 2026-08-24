
            
        async function update(key,value){
          const r=await api.post('/api/settings/update',{key,value:String(value)});
          toast(r.needRestart?'✅ 已保存 · TV 已弹重启提示':'✅ 已保存',2000);
        }
        async function load(){
          const d=await api.get('/api/settings');const items=d.items||[];const el=document.getElementById('list');
          el.innerHTML=items.map(it=>{
            const tag=it.needRestart?'<span class="tag preset">需重启</span>':'<span class="tag pending">即时</span>';
            var ctrl='';
            if(it.type==='toggle'){
              const on=(it.value==='on'||it.value==='true');
              ctrl=`<label class="switch"><input type="checkbox" ${on?'checked':''} onchange=\"update('${it.key}',this.checked?'on':'off')\"/><span class="slider"></span></label>`;
            }else if(it.type==='quality'){
              const v=it.value||'auto';const opts=['auto','1080','4k'];
              ctrl=`<select onchange=\"update('${it.key}',this.value)\">${opts.map(o=>`<option value=\"${o}\" ${v===o?'selected':''}>${o}</option>`).join('')}</select>`;
            }else if(it.type==='screensaver'){
              const v=it.value||'track';const opts=[['track','横向轨道流（默认）'],['waterfall','多列瀑布流'],['mosaic','随机砖块网格']];
              ctrl=`<select onchange=\"update('${it.key}',this.value)\">${opts.map(([o,label])=>`<option value=\"${o}\" ${v===o?'selected':''}>${label}</option>`).join('')}</select>`;
            }else if(it.type==='password'){
              ctrl=`<div class=\"row\"><input class=\"grow\" type=\"password\" placeholder=\"4-8 位数字\" value=\"${esc(it.value||'')}\" id=\"in_${it.key}\"/><button class=\"btn primary sm\" onclick=\"update('${it.key}',document.getElementById('in_${it.key}').value)\">保存</button></div>`;
            }else{
              ctrl=`<div class=\"row\"><input class=\"grow\" type=\"text\" value=\"${esc(it.value||'')}\" id=\"in_${it.key}\"/><button class=\"btn primary sm\" onclick=\"update('${it.key}',document.getElementById('in_${it.key}').value)\">保存</button></div>`;
            }
            return `<div class=\"card\"><h3>${esc(it.label)} ${tag}</h3><div class=\"muted\" style=\"margin-bottom:10px\">${esc(it.desc||'')}</div>${ctrl}</div>`;
          }).join('');
        }
        load();
        
          