#!/usr/bin/env python3
"""Blind A/B: which photo is better, Holtzman's or the Pixel Camera app's? Your votes show where Holtzman is behind.

    python3 tools/dataset/ab.py [dataset_folder]            (default ~/Code/holtzman-dataset)

Opens http://127.0.0.1:8766 . For each scene that has both Holtzman's own photo (holtzman.jpg, made by the app's dataset capture since 0.5.1) and the Pixel photo of the same
moment, it shows the two side by side with the sides shuffled and NOT labelled. Pick the better one (or "same"), say what decided it if you like, and only then is it shown which
was which. Click a picture to see the same place of both at 100%. Votes are appended to <dataset>/ab_votes.jsonl (one JSON line each; voting again on a scene replaces your
earlier vote). /summary shows the results: how often Holtzman wins overall and by scene type, ISO and reason, with 95% intervals.
Keys: 1 left, 2 right, 3 same, n next scene, s summary. Needs Pillow.
"""
import hashlib, io, json, math, os, random, sys, threading, time, webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, unquote, parse_qs
from PIL import Image, ImageOps

ROOT = os.path.abspath(os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else '~/Code/holtzman-dataset'))
PORT = int(os.environ.get('PORT', '8766'))
VOTES = os.path.join(ROOT, 'ab_votes.jsonl')
REASONS = ['less noise', 'more detail', 'better colour', 'better brightness', 'highlights', 'shadows', 'looks natural', 'sharper', 'other']
lock = threading.Lock()


def pixel_for(scene):
    cands = []
    p = os.path.join(ROOT, 'pixel_pairs.json')
    if os.path.isfile(p):
        try: cands += ['PixelDoubles/' + pix for pix, s, _ in json.load(open(p)) if s == scene]
        except Exception: pass
    cands.append(f'PixelDoubles/{scene}.jpg')
    return next((c for c in cands if os.path.isfile(os.path.join(ROOT, c))), None)


def scenes():
    out = []
    for d in sorted(os.listdir(ROOT)):
        if not d.startswith('scene_'): continue
        mp = os.path.join(ROOT, d, 'meta.json')
        if not (os.path.isfile(mp) and os.path.isfile(os.path.join(ROOT, d, 'holtzman.jpg'))): continue
        px = pixel_for(d)
        if not px: continue
        m = json.load(open(mp))
        # the side Holtzman is shown on is fixed per scene by a hash, so reloading does not change it, and it is not guessable from the name
        side = 'L' if int(hashlib.md5((d + 'ab').encode()).hexdigest(), 16) % 2 == 0 else 'R'
        out.append({'scene': d, 'pixel': px, 'side': side, 'tags': m.get('tags', []), 'iso': (m.get('autoAtStart') or {}).get('iso'), 'version': m.get('appVersion'), 'zoom': m.get('pixelZoom'), 'note': m.get('note', '')})
    return out


def votes():
    v = {}
    if os.path.isfile(VOTES):
        for line in open(VOTES):
            try: r = json.loads(line); v[r['scene']] = r   # the latest vote on a scene wins
            except Exception: pass
    return v


def path_for(scene, which):
    s = next((x for x in scenes() if x['scene'] == scene), None)
    if not s: return None
    holt = (which == s['side'])
    return os.path.join(ROOT, scene, 'holtzman.jpg') if holt else os.path.join(ROOT, s['pixel'])


def opened(path):
    return ImageOps.exif_transpose(Image.open(path)).convert('RGB')


def jpeg(im, q=88):
    b = io.BytesIO(); im.save(b, 'JPEG', quality=q); return b.getvalue()


def wilson(w, n, z=1.96):
    if n == 0: return (0.0, 0.0, 0.0)
    p = w / n; d = 1 + z * z / n; c = p + z * z / (2 * n); h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return (p, (c - h) / d, (c + h) / d)


def summary():
    v = votes(); sc = {s['scene']: s for s in scenes()}; rows = [r for r in v.values() if r['scene'] in sc]
    def rate(sel):
        sel = list(sel); decisive = [r for r in sel if r['winner'] != 'same']; w = sum(1 for r in decisive if r['winner'] == 'holtzman')
        p, lo, hi = wilson(w, len(decisive))
        return {'votes': len(sel), 'holtzman': w, 'pixel': len(decisive) - w, 'same': len(sel) - len(decisive), 'rate': p, 'lo': lo, 'hi': hi}
    tags = {}
    for r in rows:
        for t in sc[r['scene']]['tags']: tags.setdefault(t, []).append(r)
    band = lambda i: 'unknown' if i is None else 'ISO 100 or less' if i <= 100 else 'ISO 101-400' if i <= 400 else 'ISO 401-1000' if i <= 1000 else 'ISO above 1000'
    bands = {}
    for r in rows: bands.setdefault(band(sc[r['scene']]['iso']), []).append(r)
    reasons = {}
    for r in rows:
        for x in r.get('reasons', []): reasons.setdefault(x, {'holtzman': 0, 'pixel': 0})[r['winner'] if r['winner'] != 'same' else 'holtzman'] += (1 if r['winner'] != 'same' else 0)
    return {'overall': rate(rows), 'tags': {k: rate(x) for k, x in sorted(tags.items(), key=lambda kv: -len(kv[1])) if len(x) >= 3}, 'iso': {k: rate(x) for k, x in sorted(bands.items())},
            'reasons': reasons, 'total_scenes': len(sc), 'voted': len(rows)}


PAGE = r'''<!doctype html><meta charset=utf-8><title>Blind A/B</title><meta name=viewport content="width=device-width,initial-scale=1">
<style>
:root{color-scheme:dark;--bg:#111;--panel:#1c1c1e;--line:#333;--ink:#eee;--dim:#9a9a9f;--y:#ffd60a}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:14px -apple-system,system-ui,sans-serif;padding:14px 18px}
a{color:var(--y)}.top{display:flex;gap:16px;align-items:baseline;color:var(--dim)}.top b{color:var(--ink)}
#imgs{display:flex;gap:10px;margin:10px 0}.pane{flex:1;min-width:0;position:relative}.pane img{width:100%;max-height:62vh;object-fit:contain;background:#000;border-radius:8px;display:block;cursor:crosshair}
.lab{position:absolute;left:8px;top:8px;background:#000a;padding:3px 9px;border-radius:99px;font-weight:700}.reveal{color:var(--y);font-weight:700}
.btns{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin:8px 0}
button,.chip{background:var(--panel);color:var(--ink);border:1px solid var(--line);border-radius:10px;padding:9px 16px;cursor:pointer;font:inherit}
button.big{font-size:16px;font-weight:600;padding:12px 26px}button:hover{border-color:#777}.chip{border-radius:99px;padding:5px 12px}.chip.on{background:var(--y);color:#000;border-color:var(--y);font-weight:600}
input.note{background:var(--panel);border:1px solid var(--line);color:var(--ink);border-radius:10px;padding:8px;width:320px}
table{border-collapse:collapse;margin:8px 0}td,th{padding:5px 14px;border-bottom:1px solid #222;text-align:left}.dim{color:var(--dim)}
#zoom{display:none;margin-top:6px}#zoom.on{display:flex;gap:10px}
</style>
<div class=top id=top></div><div id=main></div>
<script>
let S=[],V={},cur=null,reasons=[],R=[];
const $=s=>document.querySelector(s);
async function api(p,o){const r=await fetch(p,o);return r.json()}
async function load(){const d=await api('/api/scenes');S=d.scenes;V=d.votes;R=d.reasons;next(true)}
function left(){return S.filter(s=>!V[s.scene])}
function next(first){const l=left();const pool=l.length?l:S;if(!pool.length){$('#main').innerHTML='<p>No scene has both Holtzman\'s own photo (holtzman.jpg) and a Pixel photo yet. Take paired captures with the app (version 0.5.1 or later), pull them with tools/dataset/pull.py, and run tools/dataset/pair.py.</p>';$('#top').innerHTML='';return}
 cur=pool[Math.floor(Math.random()*pool.length)];reasons=[];show()}
function show(voted){const s=cur,v=V[s.scene];
 $('#top').innerHTML=`<span><b>${Object.keys(V).length}</b> of ${S.length} scenes voted</span><a href="/summary">summary</a><span class=dim>${s.scene.replace('scene_','')} ${s.iso?'· ISO '+s.iso:''} ${s.tags.length?'· '+s.tags.join(', '):''}</span>`;
 const rv=voted||v;const lab=side=>rv?`<div class=lab>${side==s.side?'Holtzman':'Pixel'}</div>`:`<div class=lab>${side=='L'?'Left':'Right'}</div>`;
 $('#main').innerHTML=`<div id=imgs><div class=pane>${lab('L')}<img src="/img/${s.scene}/L" data-s=L></div><div class=pane>${lab('R')}<img src="/img/${s.scene}/R" data-s=R></div></div>
 <div id=zoom><div class=pane><img id=zl></div><div class=pane><img id=zr></div></div><div class=dim>Click a picture to see the same place of both at 100%.</div>
 <div class=btns><button class=big id=bl>1 · Left is better</button><button class=big id=bs>3 · Same</button><button class=big id=br>2 · Right is better</button></div>
 <div class=btns><span class=dim>what decided it:</span>${R.map(x=>`<span class="chip ${reasons.includes(x)?'on':''}" data-r="${x}">${x}</span>`).join('')}<input class=note id=note placeholder="a note (optional)"></div>
 ${rv?`<div class=reveal>${v?('Your vote: '+v.winner+(v.winner=='same'?'':' won')):''}</div><div class=btns><button id=nx>n · Next scene</button></div>`:''}`;
 document.querySelectorAll('.chip').forEach(e=>e.onclick=()=>{const r=e.dataset.r;reasons=reasons.includes(r)?reasons.filter(x=>x!=r):[...reasons,r];e.classList.toggle('on')});
 document.querySelectorAll('#imgs img').forEach(i=>i.onclick=ev=>{const b=i.getBoundingClientRect();const nat=i.naturalWidth/i.naturalHeight,box=b.width/b.height;let w=b.width,h=b.height,ox=0,oy=0;
  if(nat>box){h=w/nat;oy=(b.height-h)/2}else{w=h*nat;ox=(b.width-w)/2}const fx=Math.min(1,Math.max(0,(ev.clientX-b.left-ox)/w)),fy=Math.min(1,Math.max(0,(ev.clientY-b.top-oy)/h));
  $('#zoom').classList.add('on');$('#zl').src=`/crop/${s.scene}/L?x=${fx}&y=${fy}`;$('#zr').src=`/crop/${s.scene}/R?x=${fx}&y=${fy}`});
 $('#bl').onclick=()=>vote('L');$('#br').onclick=()=>vote('R');$('#bs').onclick=()=>vote('same');if($('#nx'))$('#nx').onclick=()=>next()}
async function vote(c){if(!cur||(V[cur.scene]&&document.querySelector('.reveal')&&!window._revote))return;const s=cur;
 const winner=c=='same'?'same':(c==s.side?'holtzman':'pixel');
 const r=await api('/api/vote',{method:'POST',body:JSON.stringify({scene:s.scene,choice:c,winner,holtzmanSide:s.side,reasons,note:$('#note').value,tags:s.tags,iso:s.iso,version:s.version})});
 if(r.ok){V[s.scene]={scene:s.scene,winner,reasons};show(true)}else alert('Not saved: '+r.error)}
document.onkeydown=e=>{if(e.target.tagName=='INPUT')return;if(e.key=='1')vote('L');else if(e.key=='2')vote('R');else if(e.key=='3')vote('same');else if(e.key=='n')next();else if(e.key=='s')location='/summary'};
load();
</script>'''

SUMMARY = r'''<!doctype html><meta charset=utf-8><title>A/B summary</title><meta name=viewport content="width=device-width,initial-scale=1">
<style>:root{color-scheme:dark}body{margin:0;background:#111;color:#eee;font:14px -apple-system,system-ui,sans-serif;padding:18px 24px}a{color:#ffd60a}table{border-collapse:collapse;margin:8px 0 22px}td,th{padding:6px 16px;border-bottom:1px solid #222;text-align:left}.bar{display:inline-block;height:10px;background:#ffd60a;border-radius:5px;vertical-align:middle}.dim{color:#9a9a9f}h2{margin:22px 0 4px;font-size:16px}</style>
<a href="/">back to voting</a><div id=o></div>
<script>
const row=(k,r)=>`<tr><td>${k}</td><td>${r.votes}</td><td>${r.holtzman}</td><td>${r.pixel}</td><td>${r.same}</td><td>${r.holtzman+r.pixel?(r.rate*100).toFixed(0)+'%':'-'} <span class=dim>[${(r.lo*100).toFixed(0)}, ${(r.hi*100).toFixed(0)}]</span></td><td><span class=bar style="width:${r.rate*140}px"></span></td></tr>`;
fetch('/api/summary').then(r=>r.json()).then(d=>{let h=`<h2>Does Holtzman win?</h2><div class=dim>Win rate = Holtzman wins out of the decisive votes (ties left out); 50% is a dead heat; the bracket is a 95% interval, wide when there are few votes. ${d.voted} of ${d.total_scenes} scenes voted.</div>`;
 const head='<table><tr><th></th><th>votes</th><th>Holtzman</th><th>Pixel</th><th>same</th><th>Holtzman win rate</th><th></th></tr>';
 h+=head+row('all scenes',d.overall)+'</table>';
 h+='<h2>By ISO</h2>'+head+Object.entries(d.iso).map(([k,r])=>row(k,r)).join('')+'</table>';
 h+='<h2>By scene type (tags)</h2>'+head+Object.entries(d.tags).map(([k,r])=>row(k,r)).join('')+'</table>';
 h+='<h2>What decided the votes you gave a reason for</h2><table><tr><th>reason</th><th>Holtzman won</th><th>Pixel won</th></tr>'+Object.entries(d.reasons).map(([k,r])=>`<tr><td>${k}</td><td>${r.holtzman}</td><td>${r.pixel}</td></tr>`).join('')+'</table><div class=dim>Where "Pixel won" is large, that is what to work on.</div>';
 document.getElementById('o').innerHTML=h})
</script>'''


class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, code, body, ctype='application/json'):
        b = body if isinstance(body, bytes) else body.encode()
        self.send_response(code); self.send_header('Content-Type', ctype); self.send_header('Content-Length', str(len(b))); self.send_header('Cache-Control', 'no-store'); self.end_headers(); self.wfile.write(b)

    def do_GET(self):
        u = urlparse(self.path); p = unquote(u.path); q = parse_qs(u.query)
        try:
            if p == '/': return self.send(200, PAGE, 'text/html; charset=utf-8')
            if p == '/summary': return self.send(200, SUMMARY, 'text/html; charset=utf-8')
            if p == '/api/summary': return self.send(200, json.dumps(summary()))
            if p == '/api/scenes':
                return self.send(200, json.dumps({'scenes': [{k: s[k] for k in ('scene', 'side', 'tags', 'iso', 'version')} for s in scenes()], 'votes': {k: {'scene': k, 'winner': v['winner'], 'reasons': v.get('reasons', [])} for k, v in votes().items()}, 'reasons': REASONS}))
            parts = p.strip('/').split('/')
            if parts[0] in ('img', 'crop') and len(parts) == 3 and parts[2] in ('L', 'R'):
                path = path_for(parts[1], parts[2])
                if not path or not os.path.isfile(path): return self.send(404, 'no')
                im = opened(path)
                if parts[0] == 'img': im.thumbnail((1500, 1500)); return self.send(200, jpeg(im), 'image/jpeg')
                fx = float(q.get('x', ['.5'])[0]); fy = float(q.get('y', ['.5'])[0]); size = 700
                cx = int(fx * im.width); cy = int(fy * im.height); x0 = max(0, min(im.width - size, cx - size // 2)); y0 = max(0, min(im.height - size, cy - size // 2))
                return self.send(200, jpeg(im.crop((x0, y0, x0 + size, y0 + size)), 92), 'image/jpeg')
            self.send(404, 'no')
        except Exception as e:
            self.send(500, json.dumps({'error': str(e)}))

    def do_POST(self):
        if urlparse(self.path).path != '/api/vote': return self.send(404, 'no')
        try:
            d = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
            if d['scene'] not in {s['scene'] for s in scenes()}: raise ValueError('unknown scene')
            if d['winner'] not in ('holtzman', 'pixel', 'same'): raise ValueError('bad vote')
            rec = {k: d.get(k) for k in ('scene', 'choice', 'winner', 'holtzmanSide', 'reasons', 'note', 'tags', 'iso', 'version')}; rec['time'] = time.strftime('%Y-%m-%d %H:%M:%S')
            with lock, open(VOTES, 'a') as f: f.write(json.dumps(rec) + '\n')
            self.send(200, json.dumps({'ok': True}))
        except Exception as e:
            self.send(200, json.dumps({'ok': False, 'error': str(e)}))


if __name__ == '__main__':
    if not os.path.isdir(ROOT): sys.exit('No dataset folder at ' + ROOT)
    n = len(scenes()); print(f'{n} scenes with both Holtzman\'s photo and a Pixel photo, {len(votes())} already voted, in {ROOT}\nOpen http://127.0.0.1:{PORT}   (Ctrl-C to stop)')
    if n == 0: print('None yet: take paired captures with app 0.5.1 or later, pull them (tools/dataset/pull.py), then run tools/dataset/pair.py.')
    srv = ThreadingHTTPServer(('127.0.0.1', PORT), H)
    if not os.environ.get('NO_BROWSER'): threading.Timer(0.6, lambda: webbrowser.open(f'http://127.0.0.1:{PORT}')).start()
    try: srv.serve_forever()
    except KeyboardInterrupt: pass
