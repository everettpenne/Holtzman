#!/usr/bin/env python3
"""Holtzman Studio: one window for every dataset, training and release tool, so nothing needs the terminal.

    python3 tools/studio.py            (or double-click "Holtzman Studio.command" in the repo's top folder)

Opens http://127.0.0.1:8770 . The page shows what state things are in (is the phone connected, how many scenes are waiting to be prepared, is the overnight training
running, is there a version nobody has released yet) and has one button for each job. A job runs the same script you would have typed, and its output appears live
in the panel at the bottom. Jobs that delete or publish something ask first, in plain words. Closing the browser tab does not stop a running job; closing this program does.

It only listens on this computer (127.0.0.1), and every action needs the page's own secret token, so another web page cannot press its buttons.
Only the Python standard library is needed. The tagging and A/B tools are started from here as small helper servers and shown in their own tabs.
"""
import json, os, re, secrets, shlex, shutil, signal, subprocess, sys, threading, time, webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HOME = os.path.expanduser('~')
DATASET = os.environ.get('HOLTZMAN_DATASET', os.path.join(HOME, 'Code', 'holtzman-dataset'))
SWEEPS = os.path.join(HOME, 'Code', 'denoiser-sweeps')
PORT = int(os.environ.get('PORT', '8770'))
TOKEN = secrets.token_urlsafe(24)
VENV_PY = os.path.join(HOME, 'Code', 'ml-venv', 'bin', 'python3')
PY = VENV_PY if os.path.exists(VENV_PY) else sys.executable   # the ML environment has numpy, TensorFlow and Pillow
ENV = dict(os.environ, PYTHONUNBUFFERED='1', PATH=os.pathsep.join([os.path.join(HOME, 'Code/android-sdk/platform-tools'), os.path.join(HOME, 'Library/Android/sdk/platform-tools'), '/opt/homebrew/bin', '/usr/local/bin', os.environ.get('PATH', '')]))
ADB = shutil.which('adb', path=ENV['PATH']) or 'adb'
lock = threading.Lock()


def run_quiet(cmd, timeout=6, cwd=None):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, cwd=cwd, env=ENV).stdout
    except Exception:
        return ''


# ---------------------------------------------------------------- what is going on (the status cards)

def newest_sweep():
    if not os.path.isdir(SWEEPS): return None
    ds = sorted(d for d in os.listdir(SWEEPS) if os.path.isdir(os.path.join(SWEEPS, d)))
    return os.path.join(SWEEPS, ds[-1]) if ds else None


def sweep_status():
    d = newest_sweep()
    if not d: return {'dir': None}
    running = bool(run_quiet(['pgrep', '-f', 'sweep.py']).strip())
    log = os.path.join(d, 'run.log'); lines = open(log).read().splitlines() if os.path.isfile(log) else []
    cfgs = [l for l in lines if ' == ' in l]
    cur = cfgs[-1].split(' == ', 1)[1] if cfgs else ''
    step = ''
    if cur:
        tl = os.path.join(d, 'train_' + cur.split()[0] + '.log')
        if os.path.isfile(tl):
            m = re.findall(r'step (\d+)/(\d+)', open(tl).read()[-3000:])
            if m: step = f'step {m[-1][0]} of {m[-1][1]}'
    done = len([f for f in os.listdir(d) if f.startswith('eval_')])
    return {'dir': d, 'running': running, 'current': cur, 'step': step, 'done': done, 'total': 8, 'report': os.path.isfile(os.path.join(d, 'sweep_report.md')), 'last': lines[-1] if lines else ''}


def dataset_status():
    if not os.path.isdir(DATASET): return {'exists': False}
    names = [n for n in os.listdir(DATASET) if n.startswith('scene_')]
    kinds = {'handheld': 0, 'clean': 0, 'ladder': 0}; unprepared = 0; hjpg = 0
    for n in names:
        k = n.rsplit('_', 1)[-1]
        if k in kinds: kinds[k] += 1
        p = os.path.join(DATASET, n)
        if not os.path.isfile(os.path.join(p, 'meta.json')): continue
        if (k == 'handheld' and not os.path.isfile(os.path.join(p, 'aligned_0.raw'))) or (k != 'handheld' and not os.path.isfile(os.path.join(p, 'params.json'))): unprepared += 1
        if os.path.isfile(os.path.join(p, 'holtzman.jpg')): hjpg += 1
    pix = [n for n in os.listdir(os.path.join(DATASET, 'PixelDoubles'))] if os.path.isdir(os.path.join(DATASET, 'PixelDoubles')) else []
    pairs = 0
    try: pairs = len(json.load(open(os.path.join(DATASET, 'pixel_pairs.json'))))
    except Exception: pass
    votes = 0
    vp = os.path.join(DATASET, 'ab_votes.jsonl')
    if os.path.isfile(vp): votes = sum(1 for _ in open(vp))
    free = shutil.disk_usage(DATASET).free // 2**30
    return {'exists': True, 'scenes': len(names), 'kinds': kinds, 'unprepared': unprepared, 'pixel': len(pix), 'pairs': pairs, 'holtzmanJpg': hjpg, 'votes': votes, 'freeGB': free}


def git_status():
    branch = run_quiet(['git', 'rev-parse', '--abbrev-ref', 'HEAD'], cwd=REPO).strip()
    dirty = len(run_quiet(['git', 'status', '--porcelain'], cwd=REPO).splitlines())
    tag = run_quiet(['git', 'tag', '--list', 'v*'], cwd=REPO).split(); tag.sort(key=lambda t: [int(x) for x in re.findall(r'\d+', t)])
    gr = open(os.path.join(REPO, 'app/build.gradle.kts')).read()
    m = re.search(r'versionName\s*=\s*"([^"]+)"', gr)
    return {'branch': branch, 'dirty': dirty, 'lastTag': tag[-1] if tag else '', 'version': m[1] if m else '?'}


STATUS = {'t': 0, 'v': {}}


def phone_status():
    out = run_quiet([ADB, 'devices', '-l'], timeout=5)
    devs = [l for l in out.splitlines()[1:] if l.strip() and 'offline' not in l and 'unauthorized' not in l]
    devs = [l for l in devs if not l.startswith('emulator-')] or devs   # prefer the real phone over an emulator
    if not devs: return {'connected': False, 'note': 'unauthorized or offline: unlock the phone and accept the prompt' if 'unauthorized' in out or 'offline' in out else 'not connected'}
    ls = run_quiet([ADB, '-s', devs[0].split()[0], 'shell', 'ls', '/sdcard/Documents/Holtzman/Dataset'], timeout=8)
    n = len([l for l in ls.splitlines() if l.startswith(('scene_',)) or l.startswith('PXL')])
    return {'connected': True, 'name': devs[0].split()[0], 'model': (re.search(r'model:(\S+)', devs[0]) or [0, ''])[1], 'items': n}


def status():
    with lock:
        if time.time() - STATUS['t'] < 4: return STATUS['v']
    v = {'phone': phone_status(), 'dataset': dataset_status(), 'sweep': sweep_status(), 'git': git_status(), 'helpers': {k: h.alive() for k, h in HELPERS.items()}}
    with lock: STATUS.update(t=time.time(), v=v)
    return v


# ---------------------------------------------------------------- jobs (a script run, with its output kept)

JOBS = {}; NEXT = [1]


class Job:
    def __init__(self, task, title, cmd, cwd):
        self.id = NEXT[0]; NEXT[0] += 1
        self.task, self.title, self.cmd, self.cwd = task, title, cmd, cwd
        self.lines = ['']; self.start = time.time(); self.end = None; self.code = None; self.cancelled = False
        self.p = subprocess.Popen(cmd, cwd=cwd, env=ENV, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        threading.Thread(target=self.read, daemon=True).start()

    def read(self):
        while True:
            b = self.p.stdout.read1(4096) if hasattr(self.p.stdout, 'read1') else self.p.stdout.read(1)
            if not b: break
            for ch in b.decode('utf-8', 'replace'):
                with lock:
                    if ch == '\n': self.lines.append('')
                    elif ch == '\r': self.lines[-1] = ''   # a progress line rewrites itself
                    else: self.lines[-1] += ch
        self.code = self.p.wait(); self.end = time.time()

    def info(self, full=False):
        d = {'id': self.id, 'task': self.task, 'title': self.title, 'running': self.code is None, 'code': self.code, 'secs': int((self.end or time.time()) - self.start), 'cancelled': self.cancelled}
        if full:
            with lock: d['text'] = '\n'.join(self.lines[-800:])
        return d

    def cancel(self):
        self.cancelled = True
        try: os.killpg(os.getpgid(self.p.pid), signal.SIGTERM)
        except Exception: pass


# ---------------------------------------------------------------- helper servers (tag and A/B)

class Helper:
    def __init__(self, script, port, title):
        self.script, self.port, self.title, self.p = script, port, title, None

    def listening(self):
        import socket
        with socket.socket() as c:
            c.settimeout(0.5)
            return c.connect_ex(('127.0.0.1', self.port)) == 0

    def alive(self): return (self.p is not None and self.p.poll() is None) or self.listening()   # also true for one started earlier by hand or by an older Studio

    def start(self):
        if not self.alive():
            self.p = subprocess.Popen([PY, os.path.join(REPO, self.script), DATASET], env=dict(ENV, PORT=str(self.port), NO_BROWSER='1'), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            time.sleep(1.2)

    def stop(self):
        if self.p is not None:
            try: os.killpg(os.getpgid(self.p.pid), signal.SIGTERM)
            except Exception: pass
            self.p = None


HELPERS = {'tag': Helper('tools/dataset/tag.py', 8765, 'Tag scenes'), 'ab': Helper('tools/dataset/ab.py', 8766, 'Blind A/B')}

# ---------------------------------------------------------------- the buttons

# fields: name, label, default, hint.   confirm: asked in the browser before it runs.   danger: red button.
GROUPS = [
    {'name': '1. Phone', 'blurb': 'Get the captures off the phone (USB cable, USB debugging on, phone unlocked).', 'tasks': [
        {'id': 'phone_check', 'title': 'Check the phone', 'desc': 'Shows whether the phone is seen by the computer and how many scenes and Pixel photos are waiting on it.', 'cmd': lambda f: ['/bin/sh', '-c', f'{shlex.quote(ADB)} devices -l; echo; S=$({shlex.quote(ADB)} devices | awk \'NR>1&&$2=="device"&&$1!~/^emulator/{{print $1}}\' | head -1); echo "Phone: $S"; echo "Items waiting on the phone:"; {shlex.quote(ADB)} -s "$S" shell ls /sdcard/Documents/Holtzman/Dataset | wc -l']},
        {'id': 'pull_dry', 'title': 'Preview the copy', 'desc': 'Says what would be copied and how big it is. Copies nothing.', 'cmd': lambda f: [PY, 'tools/dataset/pull.py', '--dry-run', '--dest', DATASET]},
        {'id': 'pull', 'title': 'Copy from the phone', 'desc': 'Copies every new scene and Pixel photo to this computer and checks each file against the phone\'s own checksum. Safe: it never deletes anything. If it is interrupted, run it again.', 'primary': True, 'cmd': lambda f: [PY, 'tools/dataset/pull.py', '--dest', DATASET]},
        {'id': 'pull_delete', 'title': 'Free up the phone', 'desc': 'Copies and re-checks everything, then DELETES from the phone only the scenes that are verified to be safely on this computer.', 'danger': True,
         'confirm': 'This deletes captured scenes from the phone, but only ones that are verified as complete and identical on this computer. Continue?', 'cmd': lambda f: [PY, 'tools/dataset/pull.py', '--dest', DATASET, '--delete-from-phone', '--yes']},
    ]},
    {'name': '2. Prepare and review the data', 'blurb': 'Turn new scenes into training data, match them with the Pixel photos, label them, and compare the two cameras.', 'tasks': [
        {'id': 'pair', 'title': 'Match Pixel photos to scenes', 'desc': 'Pairs each Pixel Camera photo with the capture taken at the same moment (by time). Pairings you fixed by hand are kept.', 'cmd': lambda f: [PY, 'tools/dataset/pair.py', DATASET]},
        {'id': 'prepare', 'title': 'Prepare new scenes for training', 'desc': 'Converts the new captures and aligns each burst. Takes about a minute per handheld scene and slows the overnight training, so best done when it is not running.', 'primary': True, 'heavy': True, 'cmd': lambda f: ['/bin/zsh', 'tools/dataset/prepare.sh', DATASET]},
        {'id': 'tag', 'title': 'Tag scenes', 'desc': 'Opens the tagging page: look at each scene next to the Pixel photo, click tags, write notes.', 'helper': 'tag'},
        {'id': 'ab', 'title': 'Blind A/B: Holtzman vs Pixel', 'desc': 'Opens the blind voting page. Needs scenes captured with app 0.5.1 or later (they carry Holtzman\'s own finished photo).', 'helper': 'ab'},
        {'id': 'look', 'title': 'Fit the colour and tone look', 'desc': 'Measures how the Pixel photos differ in tone and colour from ours and says whether the app\'s settings should change. Prints a recommendation; changes nothing.', 'fields': [{'name': 'sat', 'label': 'Saturation the old previews were made with (leave empty unless told)', 'default': ''}],
         'cmd': lambda f: [PY, 'tools/look/fit_look.py', DATASET] + (['--capture-saturation', f['sat']] if f.get('sat') else [])},
    ]},
    {'name': '3. Denoiser training', 'blurb': 'The overnight training (several models, scored on scenes they never saw) and shipping a winner.', 'tasks': [
        {'id': 'sweep_start', 'title': 'Start the overnight training', 'desc': 'Trains and scores 8 model variants one after another (about 7 to 8 hours). Results land in a new dated folder under Code/denoiser-sweeps.', 'heavy': True,
         'confirm': 'This keeps the computer busy for about 8 hours. Start it?', 'cmd': lambda f: ['/usr/bin/caffeinate', '-i', '-s', PY, 'sweep.py', os.path.join(SWEEPS, time.strftime('%Y%m%d'))], 'cwd': 'tools/denoiser'},
        {'id': 'sweep_report', 'title': 'Show the training results', 'desc': 'Rebuilds and shows the table: each model against the baseline, better / worse / no clear difference, and how much slower it would run.', 'primary': True, 'cmd': lambda f: [PY, 'tools/denoiser/sweep_report.py', newest_sweep() or SWEEPS]},
        {'id': 'heldout_add', 'title': 'Add scenes to the test set', 'desc': 'Moves some of the newest scenes into the "never trained on" test set. After this, older models can no longer be compared with new ones.', 'fields': [{'name': 'n', 'label': 'How many scenes', 'default': '5'}],
         'confirm': 'This changes the test set, so models trained before cannot be compared fairly with ones trained after. Continue?', 'cmd': lambda f: [PY, 'tools/denoiser/make_heldout.py', DATASET, '--add', f['n']]},
        {'id': 'compare', 'title': 'Is a new model better than the shipped one?', 'desc': 'Scores two models on the test scenes and says SHIP or KEEP OLD. About 8 minutes. Give the full paths of the two .keras files.', 'heavy': True, 'fields': [{'name': 'old', 'label': 'Shipped model', 'default': os.path.join(REPO, 'tools/denoiser/model.keras')}, {'name': 'new', 'label': 'New model', 'default': ''}],
         'cmd': lambda f: [PY, 'tools/denoiser/compare.py', f['old'], f['new']], 'cwd': 'tools/denoiser'},
        {'id': 'export', 'title': 'Make a model ready for the phone', 'desc': 'Converts a trained model to the phone format (float and 8-bit) and checks the 8-bit one still matches.', 'fields': [{'name': 'model', 'label': 'Model file', 'default': ''}],
         'cmd': lambda f: [PY, 'export.py', f['model']], 'cwd': 'tools/denoiser'},
    ]},
    {'name': '4. Release the app', 'blurb': 'Build a new version of Holtzman. Building publishes nothing; publishing puts it on GitHub for everyone.', 'tasks': [
        {'id': 'build', 'title': 'Build a release (not published)', 'desc': 'Checks everything is committed, runs the tests, builds the signed app and verifies it. Publishes nothing. Needs the git branch to be main.', 'primary': True, 'heavy': True,
         'fields': [{'name': 'ver', 'label': 'Version number (like 0.5.1)', 'default': ''}], 'cmd': lambda f: ['/bin/zsh', 'tools/release.sh', f['ver']]},
        {'id': 'publish', 'title': 'Build and PUBLISH to GitHub', 'desc': 'Does everything above, then commits the version, tags it and posts the app on GitHub as a pre-release, public. Cannot be taken back quietly.', 'danger': True, 'heavy': True,
         'fields': [{'name': 'ver', 'label': 'Version number', 'default': ''}, {'name': 'notes', 'label': 'What changed (shown to readers)', 'default': '', 'area': True}],
         'confirm': 'This publishes the app on GitHub where anyone can download it. Did you mean to?', 'cmd': None},
    ]},
    {'name': '5. Open folders', 'blurb': '', 'tasks': [
        {'id': 'open_dataset', 'title': 'Dataset folder', 'desc': 'Opens it in Finder.', 'open': DATASET},
        {'id': 'open_sweeps', 'title': 'Training results folder', 'desc': 'Opens it in Finder.', 'open': SWEEPS},
        {'id': 'open_docs', 'title': 'Guides', 'desc': 'Opens docs/ (data-to-release.md is the full guide).', 'open': os.path.join(REPO, 'docs')},
    ]},
]
TASKS = {t['id']: t for g in GROUPS for t in g['tasks']}


def start_task(tid, f):
    t = TASKS[tid]
    if t.get('open'): subprocess.Popen(['open', t['open']]); return {'ok': True}
    if t.get('helper'):
        h = HELPERS[t['helper']]; h.start()
        return {'ok': True, 'url': f'http://127.0.0.1:{h.port}'} if h.alive() else {'ok': False, 'error': f'{h.title} could not start. Close any other copy of it and try again.'}
    for fld in t.get('fields', []):
        if fld['name'] in ('ver', 'old', 'new', 'model', 'n') and not str(f.get(fld['name'], '')).strip(): return {'ok': False, 'error': f"Please fill in: {fld['label']}"}
    if tid in ('build', 'publish') and not re.fullmatch(r'\d+\.\d+\.\d+', f['ver'].strip()): return {'ok': False, 'error': 'The version must look like 0.5.1'}
    if tid == 'heldout_add' and not f['n'].isdigit(): return {'ok': False, 'error': 'How many must be a number'}
    for k in ('old', 'new', 'model'):
        if f.get(k) and not os.path.isfile(os.path.expanduser(f[k])): return {'ok': False, 'error': f'No such file: {f[k]}'}
    with lock:
        busy = [j for j in JOBS.values() if j.code is None and j.task == tid]
    if busy: return {'ok': False, 'error': 'That job is already running.'}
    if tid == 'sweep_start' and sweep_status().get('running'): return {'ok': False, 'error': 'The overnight training is already running.'}
    if tid == 'publish':
        nf = os.path.join(SWEEPS, f'.notes-{int(time.time())}.md'); os.makedirs(SWEEPS, exist_ok=True); open(nf, 'w').write(f['notes'])
        cmd = ['/bin/zsh', 'tools/release.sh', f['ver'].strip(), '--publish', '--notes', nf]
    else:
        f = {k: os.path.expanduser(v) if k in ('old', 'new', 'model') else v.strip() for k, v in f.items()}
        cmd = t['cmd'](f)
    cwd = os.path.join(REPO, t['cwd']) if t.get('cwd') else REPO
    if cmd[0] == '/bin/zsh' and tid in ('build', 'publish', 'prepare'): cmd[1] = os.path.join(REPO, cmd[1])
    j = Job(tid, t['title'], cmd, cwd)
    with lock: JOBS[j.id] = j
    return {'ok': True, 'job': j.id}


# ---------------------------------------------------------------- the page

PAGE = r'''<!doctype html><meta charset=utf-8><title>Holtzman Studio</title><meta name=viewport content="width=device-width,initial-scale=1">
<style>
:root{color-scheme:dark;--bg:#111113;--panel:#1c1c1e;--line:#2c2c2e;--ink:#f2f2f7;--dim:#98989f;--y:#ffd60a;--g:#30d158;--r:#ff453a;--b:#0a84ff}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.45 -apple-system,system-ui,sans-serif;padding:0 0 38vh}
header{padding:18px 28px 6px}h1{margin:0;font-size:22px}h1 span{color:var(--y)}.sub{color:var(--dim)}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:12px;padding:12px 28px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:12px 16px}.card h3{margin:0 0 4px;font-size:12px;letter-spacing:.06em;text-transform:uppercase;color:var(--dim)}
.big{font-size:17px;font-weight:600}.dot{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:7px;background:var(--dim)}.ok .dot{background:var(--g)}.bad .dot{background:var(--r)}.warn .dot{background:var(--y)}
.small{color:var(--dim);font-size:12.5px}.hint{color:var(--y);font-size:12.5px}
section{padding:6px 28px}section h2{font-size:16px;margin:18px 0 0}section p{margin:2px 0 8px;color:var(--dim)}
.tasks{display:grid;grid-template-columns:repeat(auto-fill,minmax(310px,1fr));gap:12px}
.task{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:13px 15px;display:flex;flex-direction:column;gap:8px}.task b{font-size:14.5px}.task .d{color:var(--dim);font-size:13px;flex:1}
button{font:inherit;font-weight:600;border:0;border-radius:10px;padding:8px 14px;background:#3a3a3c;color:var(--ink);cursor:pointer}button:hover{filter:brightness(1.2)}button:disabled{opacity:.4;cursor:default}
button.primary{background:var(--y);color:#000}button.danger{background:var(--r);color:#fff}.row{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
label{display:block;color:var(--dim);font-size:12px}input,textarea{width:100%;background:#0c0c0e;color:var(--ink);border:1px solid var(--line);border-radius:8px;padding:7px 9px;font:inherit}
#console{position:fixed;left:0;right:0;bottom:0;height:36vh;min-height:150px;background:#0a0a0b;border-top:1px solid var(--line);display:flex;flex-direction:column}
#tabs{display:flex;gap:6px;padding:8px 14px;border-bottom:1px solid var(--line);overflow-x:auto;align-items:center}#tabs .t{padding:4px 11px;border-radius:99px;background:#262628;cursor:pointer;white-space:nowrap;font-size:12.5px}#tabs .t.on{background:var(--y);color:#000;font-weight:600}
pre{margin:0;padding:10px 16px;overflow:auto;flex:1;font:12.5px/1.4 ui-monospace,Menlo,monospace;white-space:pre-wrap;word-break:break-word}
.fin{color:var(--g)}.fail{color:var(--r)}#bar{padding:6px 16px;font-size:12.5px;border-top:1px solid var(--line);color:var(--dim);display:flex;gap:12px;align-items:center}
</style>
<header><h1>Holtzman <span>Studio</span></h1><div class=sub>Every tool in one place. Start at the top, work down. Each button says what it does; the ones that delete or publish ask first.</div></header>
<div class=cards id=cards></div><div id=groups></div>
<div id=console><div id=tabs></div><pre id=log>Press a button above. Its output appears here.</pre><div id=bar><span id=state></span><button id=cancel style="display:none">Stop this job</button></div></div>
<script>
const TOKEN="__TOKEN__",G=__GROUPS__;let jobs=[],cur=null,st={};
const $=s=>document.querySelector(s);
const post=(p,b)=>fetch(p,{method:'POST',headers:{'X-Token':TOKEN},body:JSON.stringify(b||{})}).then(r=>r.json());
function esc(s){return String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}
function drawGroups(){$('#groups').innerHTML=G.map(g=>`<section><h2>${g.name}</h2><p>${g.blurb}</p><div class=tasks>${g.tasks.map(t=>`<div class=task><b>${t.title}</b><div class=d>${t.desc}</div>
 ${(t.fields||[]).map(f=>`<div><label>${f.label}</label>${f.area?`<textarea rows=3 id="f_${t.id}_${f.name}">${esc(f.default)}</textarea>`:`<input id="f_${t.id}_${f.name}" value="${esc(f.default)}">`}</div>`).join('')}
 <div class=row><button class="${t.danger?'danger':t.primary?'primary':''}" onclick="go('${t.id}')">${t.helper?'Open':t.open?'Open':t.danger?'Run…':'Run'}</button>${t.helper?`<button id="stop_${t.id}" onclick="stopH('${t.helper}')" style="display:none">Stop helper</button>`:''}</div></div>`).join('')}</div></section>`).join('')}
async function go(id){const t=G.flatMap(g=>g.tasks).find(x=>x.id==id);const f={};(t.fields||[]).forEach(x=>f[x.name]=document.getElementById(`f_${id}_${x.name}`).value);
 if(t.confirm&&!confirm(t.confirm))return;
 const w=t.helper?window.open('about:blank','_blank'):null;   // opened now, while the click still counts, so the browser's pop-up blocker lets it through
 const r=await post('/api/run/'+id,f);
 if(!r.ok){if(w)w.close();alert(r.error||'Could not start');return}
 if(r.url){if(w)w.location=r.url;else location.href=r.url;return}if(r.job){cur=r.job;await poll()}}
async function stopH(h){await post('/api/stop_helper/'+h);refresh()}
async function poll(){const r=await (await fetch('/api/jobs')).json();jobs=r.jobs;if(cur==null&&jobs.length)cur=jobs[jobs.length-1].id;
 $('#tabs').innerHTML=jobs.slice(-10).map(j=>`<span class="t ${j.id==cur?'on':''}" onclick="cur=${j.id};poll()">${j.running?'● ':j.code===0?'✓ ':'✗ '}${esc(j.title)}</span>`).join('')||'<span class=small>no jobs yet</span>';
 const j=jobs.find(x=>x.id==cur);if(!j)return;const f=await (await fetch('/api/job/'+cur)).json();const el=$('#log'),bottom=el.scrollTop+el.clientHeight>=el.scrollHeight-40;el.textContent=f.text;if(bottom)el.scrollTop=el.scrollHeight;
 $('#state').innerHTML=j.running?`Running… ${j.secs}s`:j.cancelled?'<span class=fail>Stopped.</span>':j.code===0?`<span class=fin>Finished OK</span> in ${j.secs}s`:`<span class=fail>Failed (code ${j.code})</span> — read the last lines above`;
 $('#cancel').style.display=j.running?'':'none';$('#cancel').onclick=()=>post('/api/cancel/'+j.id)}
function card(t,cls,big,small,hint){return `<div class="card ${cls}"><h3>${t}</h3><div class=big><span class=dot></span>${big}</div><div class=small>${small||''}</div>${hint?`<div class=hint>${hint}</div>`:''}</div>`}
async function refresh(){st=await (await fetch('/api/status')).json();const p=st.phone,d=st.dataset,s=st.sweep,g=st.git;let h='';
 h+=p.connected?card('Phone','ok',(p.model||'Connected').replace(/_/g,' '),`${p.items} items waiting on it`,p.items?'Section 1 → Copy from the phone':''):card('Phone','bad','Not connected',p.note,'Plug in, unlock, allow USB debugging');
 if(d.exists)h+=card('Dataset',d.unprepared?'warn':'ok',`${d.scenes} scenes`,`${d.kinds.handheld} handheld, ${d.kinds.clean} clean, ${d.kinds.ladder} ladder · ${d.pixel} Pixel photos, ${d.pairs} paired · ${d.freeGB} GB free`,d.unprepared?`${d.unprepared} new scenes need “Prepare”`:'');
 else h+=card('Dataset','bad','Not found','');
 if(d.exists)h+=card('Holtzman vs Pixel',d.holtzmanJpg?'ok':'warn',`${d.holtzmanJpg} scenes with Holtzman’s photo`,`${d.votes} A/B votes so far`,d.holtzmanJpg?'':'Capture pairs with app 0.5.1 to use the blind A/B');
 if(s.dir)h+=card('Overnight training',s.running?'warn':'ok',s.running?'Running':'Not running',s.running?`${esc(s.current)} · ${s.step}<br>${s.done} of ${s.total} scored`:`${s.done} of ${s.total} scored`,s.running?'Avoid heavy jobs until it finishes':s.report?'Results ready: section 3':'');
 h+=card('App',g.dirty?'warn':'ok',`Version ${g.version}`,`last release ${g.lastTag||'none'} · branch ${g.branch}`+(g.version!=(g.lastTag||'').slice(1)?'':''),g.version!=(g.lastTag||'').slice(1)?'This version is not published yet':'');
 $('#cards').innerHTML=h;for(const k in st.helpers){const b=document.getElementById('stop_'+k);if(b)b.style.display=st.helpers[k]?'':'none'}}
drawGroups();refresh();poll();setInterval(refresh,6000);setInterval(()=>{if(jobs.some(j=>j.running)||true)poll()},1500);
</script>'''


def groups_json():
    out = []
    for g in GROUPS:
        out.append({'name': g['name'], 'blurb': g['blurb'], 'tasks': [{k: v for k, v in t.items() if k != 'cmd'} for t in g['tasks']]})
    return json.dumps(out)


class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def send(self, code, body, ctype='application/json'):
        b = body if isinstance(body, bytes) else body.encode()
        self.send_response(code); self.send_header('Content-Type', ctype); self.send_header('Content-Length', str(len(b))); self.send_header('Cache-Control', 'no-store'); self.end_headers(); self.wfile.write(b)

    def host_ok(self): return self.headers.get('Host', '') in (f'127.0.0.1:{PORT}', f'localhost:{PORT}')

    def do_GET(self):
        if not self.host_ok(): return self.send(403, 'no')
        p = urlparse(self.path).path
        if p == '/': return self.send(200, PAGE.replace('__TOKEN__', TOKEN).replace('__GROUPS__', groups_json()), 'text/html; charset=utf-8')
        if p == '/api/status': return self.send(200, json.dumps(status()))
        if p == '/api/jobs': return self.send(200, json.dumps({'jobs': [j.info() for j in JOBS.values()]}))
        m = re.fullmatch(r'/api/job/(\d+)', p)
        if m and int(m[1]) in JOBS: return self.send(200, json.dumps(JOBS[int(m[1])].info(True)))
        self.send(404, 'no')

    def do_POST(self):
        if not self.host_ok() or self.headers.get('X-Token') != TOKEN: return self.send(403, json.dumps({'ok': False, 'error': 'forbidden'}))
        p = urlparse(self.path).path
        try: body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))) or '{}')
        except Exception: body = {}
        try:
            m = re.fullmatch(r'/api/run/(\w+)', p)
            if m and m[1] in TASKS: return self.send(200, json.dumps(start_task(m[1], body)))
            m = re.fullmatch(r'/api/cancel/(\d+)', p)
            if m and int(m[1]) in JOBS: JOBS[int(m[1])].cancel(); return self.send(200, '{"ok":true}')
            m = re.fullmatch(r'/api/stop_helper/(\w+)', p)
            if m and m[1] in HELPERS: HELPERS[m[1]].stop(); STATUS['t'] = 0; return self.send(200, '{"ok":true}')
        except Exception as e:
            return self.send(200, json.dumps({'ok': False, 'error': f'{type(e).__name__}: {e}'}))
        self.send(404, 'no')


def main():
    signal.signal(signal.SIGTERM, lambda *a: sys.exit(0))   # closing the window or killing the program also stops the helper servers
    srv = ThreadingHTTPServer(('127.0.0.1', PORT), H)
    print(f'Holtzman Studio is running at http://127.0.0.1:{PORT}\nLeave this window open while you use it; close it (or press Ctrl-C) to stop. Running jobs keep their own output in the page.')
    if not os.environ.get('NO_BROWSER'): threading.Timer(0.6, lambda: webbrowser.open(f'http://127.0.0.1:{PORT}')).start()
    try: srv.serve_forever()
    except (KeyboardInterrupt, SystemExit): pass
    finally:
        for h in HELPERS.values(): h.stop()


if __name__ == '__main__':
    main()
