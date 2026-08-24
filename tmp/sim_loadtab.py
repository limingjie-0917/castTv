import re, subprocess, os

src = open('app/src/main/java/com/bd/casttv/dlna/HtmlPages.kt', encoding='utf-8').read()

m = re.search(r'<script>(.*?)</script>', src, re.DOTALL)
common = m.group(1)
blocks = re.findall(r'val script = """(.*?)"""', src, re.DOTALL)
names = ['cloud','favorites','history','queue','settings']
marker='//__PAGE_SCRIPT_START__'

os.makedirs('tmp/js', exist_ok=True)
for i,b in enumerate(blocks):
    # Build the actual served <script> textContent: common has `$extraScript` placeholder
    full_script = common.replace('$extraScript', b).replace("${'$'}","$")
    txt = full_script
    for mode in ['indexOf','lastIndexOf']:
        idx = txt.index(marker) if mode=='indexOf' else txt.rindex(marker)
        evaled = txt[idx+len(marker):]
        evaled = re.sub(r'\blet\s+','var ',evaled)
        path=f'tmp/js/eval_{names[i]}_{mode}.js'
        open(path,'w',encoding='utf-8').write(evaled)
        r=subprocess.run(['node','--check',path],capture_output=True,text=True)
        st='OK' if r.returncode==0 else 'FAIL: '+r.stderr.strip().splitlines()[-1] if r.stderr.strip() else 'FAIL'
        # get the acorn message too
        print(f'{names[i]:10s} {mode:12s} -> {"OK" if r.returncode==0 else r.stderr.strip().splitlines()[0] if r.stderr.strip() else "FAIL"}')
