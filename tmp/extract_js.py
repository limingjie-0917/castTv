import re, subprocess, os

src = open('app/src/main/java/com/bd/casttv/dlna/HtmlPages.kt', encoding='utf-8').read()

# find all `val script = """ ... """` blocks (non-greedy across lines)
pattern = re.compile(r'val script = """(.*?)"""', re.DOTALL)
blocks = pattern.findall(src)
print("found script blocks:", len(blocks))

os.makedirs('tmp/js', exist_ok=True)
names = ['cloud','favorites','history','queue','settings']
for i, b in enumerate(blocks):
    js = b.replace("${'$'}", "$")
    name = names[i] if i < len(names) else f'block{i}'
    path = f'tmp/js/{name}.js'
    open(path, 'w', encoding='utf-8').write(js)
    r = subprocess.run(['node','--check',path], capture_output=True, text=True)
    status = 'OK' if r.returncode==0 else 'FAIL'
    print(f'--- {name}: {status}')
    if r.returncode!=0:
        print(r.stderr.strip()[:800])
