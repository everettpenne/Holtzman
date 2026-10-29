#!/usr/bin/env python3
"""Tag and annotate dataset scenes in your browser.

    python3 tools/dataset/tag.py [dataset_folder]        (default: ~/Code/holtzman-dataset)

Opens http://127.0.0.1:8765 . Each scene shows its preview and, when there is one, the Pixel Camera app's photo of the same moment; click tags to toggle
them, type a note, and it saves as you go. Only the "tags" and "note" of each scene's meta.json are ever written (atomically, keeping the rest as it was),
so nothing the training reads can be damaged. Keys: j / k or the arrows for next / previous scene, / to search the tag box, n to jump to the note.
Needs only Python 3; Pillow, if installed, makes the thumbnails small and fast.
"""
import json, os, sys, io, re, tempfile, threading, webbrowser, hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, unquote

ROOT = os.path.abspath(os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else '~/Code/holtzman-dataset'))
PORT = int(os.environ.get('PORT', '8765'))
CACHE = os.path.join(tempfile.gettempdir(), 'holtzman-tag-thumbs')
os.makedirs(CACHE, exist_ok=True)
try:
    from PIL import Image, ImageOps
except Exception:
    Image = None

# the app's own chips first, then ones for what the dataset still lacks
LIGHT = ['sun', 'shade', 'overcast', 'indoor warm', 'indoor daylight', 'dim', 'night', 'backlit']
SUBJECT = ['people', 'skin', 'text', 'foliage', 'fabric', 'food', 'texture', 'sky', 'moving subject', 'low contrast']
EXTRA = ['outdoor', 'indoor', 'face', 'motion', 'tripod', 'handheld', 'ultrawide', 'zoomed', 'high contrast', 'sunset', 'pet', 'water']
lock = threading.Lock()


def scene_dirs():
    return sorted(d for d in os.listdir(ROOT) if d.startswith('scene_') and os.path.isfile(os.path.join(ROOT, d, 'meta.json')))


def pixel_map():
    """scene -> the Pixel photos' paths under ROOT (a list: two photos can belong to one scene), from pixel_pairs.json (the older, time-matched pairs)
    and from PixelDoubles files named after their scene (paired captures)."""
    m = {}
    p = os.path.join(ROOT, 'pixel_pairs.json')
    if os.path.isfile(p):
        try:
            for pix, scene, _ in json.load(open(p)):
                m.setdefault(scene, []).append('PixelDoubles/' + pix)
        except Exception:
            pass
    d = os.path.join(ROOT, 'PixelDoubles')
    if os.path.isdir(d):
        for f in sorted(os.listdir(d)):
            if f.startswith('scene_') and f.lower().endswith('.jpg'):
                l = m.setdefault(f[:-4], [])
                if 'PixelDoubles/' + f not in l: l.append('PixelDoubles/' + f)
    return m


def read_meta(scene):
    with open(os.path.join(ROOT, scene, 'meta.json')) as f:
        return json.load(f)


def summary(scene, pix):
    m = read_meta(scene)
    paths = list(pix.get(scene, []))
    px = m.get('pixelDouble')
    if px and 'PixelDoubles/' + os.path.basename(px) not in paths: paths.append('PixelDoubles/' + os.path.basename(px))
    paths = [p for p in paths if os.path.isfile(os.path.join(ROOT, p))]
    return {'scene': scene, 'kind': m.get('kind', ''), 'tags': m.get('tags', []), 'note': m.get('note', ''),
            'preview': os.path.isfile(os.path.join(ROOT, scene, 'preview.jpg')), 'pixel': paths,
            'shake': m.get('shakeRateAtStart'), 'iso': (m.get('autoAtStart') or {}).get('iso'), 'frames': len(m.get('frames', [])), 'zoom': m.get('zoom')}


def save(scene, tags, note):
    """Rewrite only tags and note of the scene's meta.json, keeping every other field as it was, by writing a temporary file and renaming it over."""
    path = os.path.join(ROOT, scene, 'meta.json')
    with lock:
        m = read_meta(scene)
        m['tags'] = sorted({t.strip() for t in tags if isinstance(t, str) and t.strip()})
        m['note'] = str(note)
        fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), prefix='.meta.', suffix='.tmp')
        with os.fdopen(fd, 'w') as f: json.dump(m, f, indent=2)
        os.replace(tmp, path)
    return m['tags']


def thumb(rel, size=900):   # size 0: the picture as it is (for the enlarged view)
    src = os.path.join(ROOT, rel)
    if Image is None or size == 0: return open(src, 'rb').read()
    key = hashlib.md5((src + str(os.path.getmtime(src)) + str(size)).encode()).hexdigest() + '.jpg'
    cp = os.path.join(CACHE, key)
    if not os.path.isfile(cp):
        im = ImageOps.exif_transpose(Image.open(src)).convert('RGB'); im.thumbnail((size, size)); im.save(cp, quality=85)
    return open(cp, 'rb').read()


PAGE = r'''<!doctype html><meta charset=utf-8><title>Tag the dataset</title><meta name=viewport content="width=device-width,initial-scale=1">
<style>
:root{color-scheme:dark;--bg:#111;--panel:#1c1c1e;--line:#333;--ink:#eee;--dim:#9a9a9f;--y:#ffd60a}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:14px -apple-system,system-ui,sans-serif;display:flex;height:100vh}
#list{width:300px;border-right:1px solid var(--line);display:flex;flex-direction:column}
#bar{padding:10px;border-bottom:1px solid var(--line);display:flex;gap:8px;flex-wrap:wrap;align-items:center}
#bar input[type=search]{flex:1;min-width:120px;background:var(--panel);border:1px solid var(--line);color:var(--ink);border-radius:8px;padding:6px 8px}
#rows{overflow:auto;flex:1}.row{padding:8px 10px;border-bottom:1px solid #222;cursor:pointer;display:flex;gap:8px;align-items:center}
.row:hover{background:#1a1a1c}.row.sel{background:#2a2a2d}.row .n{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.dot{width:8px;height:8px;border-radius:50%;background:#444;flex:none}.dot.has{background:var(--y)}
.small{color:var(--dim);font-size:12px}
#main{flex:1;overflow:auto;padding:16px 20px}
h2{margin:0 0 4px;font-size:16px}.imgs{display:flex;gap:12px;margin:12px 0;flex-wrap:wrap}
.imgs figure{margin:0;flex:1;min-width:280px}.imgs img{width:100%;border-radius:10px;background:#222;display:block;cursor:zoom-in}
#big{position:fixed;inset:0;background:#000d;display:none;align-items:center;justify-content:center;z-index:9;cursor:zoom-out}#big img{max-width:96vw;max-height:96vh;border-radius:6px}.imgs figcaption{color:var(--dim);font-size:12px;margin-top:4px}
.chips{display:flex;flex-wrap:wrap;gap:6px;margin:6px 0 14px}.chip{padding:5px 11px;border-radius:99px;border:1px solid #444;background:var(--panel);cursor:pointer;user-select:none}
.chip.on{background:var(--y);color:#000;border-color:var(--y);font-weight:600}
textarea{width:100%;min-height:70px;background:var(--panel);border:1px solid var(--line);color:var(--ink);border-radius:10px;padding:8px;font:inherit}
button{background:var(--panel);color:var(--ink);border:1px solid var(--line);border-radius:8px;padding:6px 10px;cursor:pointer}button:hover{border-color:#666}
#status{position:fixed;right:14px;bottom:12px;color:var(--dim)}label{color:var(--dim)}
</style>
<div id=list><div id=bar><input id=q type=search placeholder="Search scenes, tags, notes"><label><input type=checkbox id=untagged> untagged only</label><label><input type=checkbox id=withpix> with Pixel photo</label><span class=small id=count></span></div><div id=rows></div></div>
<div id=main><div class=small>Loading…</div></div><div id=big><img></div><div id=status></div>
<script>
let S=[],cur=null,V={light:[],subject:[],extra:[]};
const $=s=>document.querySelector(s),st=$('#status');
async function api(p,o){const r=await fetch(p,o);return r.json()}
function shown(){const q=$('#q').value.toLowerCase(),u=$('#untagged').checked;return S.filter(s=>(!u||!s.tags.length)&&(!$('#withpix').checked||s.pixel.length)&&(!q||(s.scene+' '+s.tags.join(' ')+' '+s.note+' '+s.kind).toLowerCase().includes(q)))}
function rows(){const list=shown();$('#count').textContent=list.length+' of '+S.length;$('#rows').innerHTML=list.map(s=>`<div class="row ${cur&&cur.scene==s.scene?'sel':''}" data-s="${s.scene}"><span class="dot ${s.tags.length?'has':''}"></span><span class=n>${s.scene.replace('scene_','')}</span><span class=small>${s.pixel.length?'P ':''}${s.kind.toLowerCase().slice(0,5)}</span></div>`).join('');
 document.querySelectorAll('.row').forEach(e=>e.onclick=()=>open(e.dataset.s))}
function open(name){cur=S.find(s=>s.scene==name);rows();const s=cur,all=[...V.light,...V.subject,...V.extra];const extra=s.tags.filter(t=>!all.includes(t));
 const chip=t=>`<span class="chip ${s.tags.includes(t)?'on':''}" data-t="${t}">${t}</span>`;
 $('#main').innerHTML=`<h2>${s.scene}</h2><div class=small>${s.kind} · ${s.frames} frames${s.iso?' · ISO '+s.iso:''}${s.shake!=null?' · shake '+s.shake.toFixed(4)+' rad/s':''}${s.zoom?' · zoom '+(+s.zoom).toFixed(1):''}</div>
 <div class=imgs>${s.preview?`<figure><img src="/img/${s.scene}/preview.jpg"><figcaption>Holtzman preview</figcaption></figure>`:'<figure><div class=small>No preview</div></figure>'}${s.pixel.map(p=>`<figure><img src="/img/${p}"><figcaption>Pixel Camera app${s.pixel.length>1?' ('+p.split('/').pop()+')':''}</figcaption></figure>`).join('')}</div>${s.pixel.length?'':'<div class=small>No Pixel photo for this scene.</div>'}
 <div class=small>Lighting</div><div class=chips>${V.light.map(chip).join('')}</div><div class=small>Subject</div><div class=chips>${V.subject.map(chip).join('')}</div>
 <div class=small>Also</div><div class=chips>${[...V.extra,...extra].map(chip).join('')}<span><input id=newtag placeholder="add a tag…" style="background:#1c1c1e;border:1px solid #333;color:#eee;border-radius:99px;padding:5px 11px"></span></div>
 <div class=small>Note</div><textarea id=note placeholder="What and where">${s.note.replace(/</g,'&lt;')}</textarea>
 <p><button id=prev>← Previous</button> <button id=next>Next →</button> <button id=copy>Copy tags from previous scene</button> <button id=clear>Clear tags</button></p>`;

 document.querySelectorAll('.imgs img').forEach(i=>i.onclick=()=>{$('#big img').src=i.src+'?big';$('#big').style.display='flex'});
 document.querySelectorAll('.chip').forEach(e=>e.onclick=()=>{const t=e.dataset.t;s.tags=s.tags.includes(t)?s.tags.filter(x=>x!=t):[...s.tags,t];push()});
 $('#newtag').onkeydown=e=>{if(e.key=='Enter'&&e.target.value.trim()){const t=e.target.value.trim();if(!s.tags.includes(t))s.tags.push(t);push()}e.stopPropagation()};
 let timer;$('#note').oninput=e=>{s.note=e.target.value;clearTimeout(timer);timer=setTimeout(()=>push(true),500)};$('#note').onkeydown=e=>e.stopPropagation();
 $('#prev').onclick=()=>step(-1);$('#next').onclick=()=>step(1);$('#clear').onclick=()=>{s.tags=[];push()};
 $('#copy').onclick=()=>{const l=shown(),i=l.findIndex(x=>x.scene==s.scene);if(i>0){s.tags=[...new Set([...s.tags,...l[i-1].tags])];push()}}}
async function push(keepFocus){const s=cur;st.textContent='saving…';const r=await api('/api/save',{method:'POST',body:JSON.stringify({scene:s.scene,tags:s.tags,note:s.note})});
 if(r.ok){s.tags=r.tags;st.textContent='saved';setTimeout(()=>st.textContent='',900)}else st.textContent='NOT saved: '+r.error;
 if(keepFocus){rows();return}const y=$('#main').scrollTop;open(s.scene);$('#main').scrollTop=y}
function step(d){const l=shown(),i=l.findIndex(x=>cur&&x.scene==cur.scene);const n=l[Math.max(0,Math.min(l.length-1,i+d))];if(n)open(n.scene)}
$('#big').onclick=()=>$('#big').style.display='none';
document.onkeydown=e=>{if(e.key=='Escape')$('#big').style.display='none';if(e.key=='j'||e.key=='ArrowDown'||e.key=='ArrowRight')step(1);else if(e.key=='k'||e.key=='ArrowUp'||e.key=='ArrowLeft')step(-1);else if(e.key=='n'){e.preventDefault();$('#note')&&$('#note').focus()}else if(e.key=='/'){e.preventDefault();$('#newtag')&&$('#newtag').focus()}}
$('#q').oninput=rows;$('#untagged').onchange=rows;$('#withpix').onchange=rows;
(async()=>{const d=await api('/api/scenes');S=d.scenes;V=d.vocab;rows();const f=S.find(s=>!s.tags.length)||S[0];if(f)open(f.scene)})();
</script>'''


class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def send(self, code, body, ctype='application/json'):
        b = body if isinstance(body, bytes) else body.encode()
        self.send_response(code); self.send_header('Content-Type', ctype); self.send_header('Content-Length', str(len(b))); self.end_headers(); self.wfile.write(b)

    def do_GET(self):
        p = unquote(urlparse(self.path).path)
        try:
            if p == '/': return self.send(200, PAGE, 'text/html; charset=utf-8')
            if p == '/api/scenes':
                pix = pixel_map()
                return self.send(200, json.dumps({'scenes': [summary(s, pix) for s in scene_dirs()], 'vocab': {'light': LIGHT, 'subject': SUBJECT, 'extra': EXTRA}}))
            if p.startswith('/img/'):
                rel = os.path.normpath(p[5:])
                full = os.path.abspath(os.path.join(ROOT, rel))
                if not full.startswith(ROOT + os.sep) or not os.path.isfile(full) or not full.lower().endswith('.jpg'): return self.send(404, 'no')
                return self.send(200, thumb(rel, 2400 if 'big' in urlparse(self.path).query else 900), 'image/jpeg')
            self.send(404, 'no')
        except Exception as e:
            self.send(500, json.dumps({'error': str(e)}))

    def do_POST(self):
        if urlparse(self.path).path != '/api/save': return self.send(404, 'no')
        try:
            d = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
            scene = d['scene']
            if scene not in scene_dirs(): raise ValueError('unknown scene')
            self.send(200, json.dumps({'ok': True, 'tags': save(scene, d.get('tags', []), d.get('note', ''))}))
        except Exception as e:
            self.send(200, json.dumps({'ok': False, 'error': str(e)}))


if __name__ == '__main__':
    if not os.path.isdir(ROOT): sys.exit('No dataset folder at ' + ROOT)
    srv = ThreadingHTTPServer(('127.0.0.1', PORT), H)
    print(f'{len(scene_dirs())} scenes in {ROOT}\nOpen http://127.0.0.1:{PORT}   (Ctrl-C to stop)')
    if not os.environ.get('NO_BROWSER'): threading.Timer(0.6, lambda: webbrowser.open(f'http://127.0.0.1:{PORT}')).start()
    try: srv.serve_forever()
    except KeyboardInterrupt: pass
