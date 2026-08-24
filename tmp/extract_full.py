import re, subprocess, os

src = open('app/src/main/java/com/bd/casttv/dlna/HtmlPages.kt', encoding='utf-8').read()

# Extract the common script inside frame(): between "<script>" and "</script>"
m = re.search(r'<script>(.*?)</script>', src, re.DOTALL)
common = m.group(1)
# The common block ends with marker line then `$extraScript`
# Split at marker
marker='//__PAGE_SCRIPT_START__'
idx = common.index(marker)
common_pre = common[:idx+len(marker)]   # includes everything up to marker

# extract extraScripts
blocks = re.findall(r'val script = """(.*?)"""', src, re.DOTALL)
names = ['cloud','favorites','history','queue','settings']

os.makedirs('tmp/js', exist_ok=True)
for i,b in enumerate(blocks):
    extra = b
    full = common_pre + "\n" + extra
    full = full.replace("${'$'}", "$")
    # frame also has other Kotlin interpolations in common? only activeMark in body(html), title in <title> - those are outside <script>. Good.
    name = names[i]
    path=f'tmp/js/full_{name}.js'
    open(path,'w',encoding='utf-8').write(full)
    r5 = subprocess.run(['node','-e',f'''
const acorn=require("acorn");const fs=require("fs");const code=fs.readFileSync("{path}","utf8");
function tryv(v){{try{{acorn.parse(code,{{ecmaVersion:v}});return "OK";}}catch(e){{return e.message;}}}}
console.log("ES5:",tryv(5));
console.log("ES2020:",tryv(2020));
'''],capture_output=True,text=True)
    print(f'=== {name} ===')
    print(r5.stdout.strip())
    if r5.stderr.strip(): print("ERR",r5.stderr.strip()[:300])
