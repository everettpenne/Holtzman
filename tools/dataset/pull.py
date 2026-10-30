#!/usr/bin/env python3
"""Copy the dataset off the phone onto this computer, check every file, and (only if you ask) delete what was copied from the phone.

    python3 tools/dataset/pull.py                       # copy what is new, verify it
    python3 tools/dataset/pull.py --dry-run             # say what it would do, copy nothing
    python3 tools/dataset/pull.py --delete-from-phone   # copy, verify everything, then offer to delete the verified scenes from the phone

What it does, in order:
  1. finds the phone over adb (exactly one device, or pass --serial);
  2. lists every file in Documents/Holtzman/Dataset on the phone with its size;
  3. copies each file that is missing or a different size here, to a ".part" name that is renamed when complete, so an interrupted run leaves no half files
     and the next run just carries on;
  4. checks the files it copied against the phone's own SHA-256 of them (sizes alone if the phone has no sha256sum);
  5. with --delete-from-phone: for each scene whose every file is on this computer with a matching checksum and whose meta.json is present (a scene
     without one is still being captured or was interrupted), asks, then deletes that scene from the phone. Files that are not verified are never deleted.
It never deletes anything on this computer.
"""
import argparse, hashlib, os, shutil, subprocess, sys, time

PHONE_DIR = '/sdcard/Documents/Holtzman/Dataset'
DEFAULT_DEST = os.path.expanduser('~/Code/holtzman-dataset')


def find_adb(explicit):
    for c in (explicit, os.environ.get('ADB'), os.path.expanduser('~/Code/android-sdk/platform-tools/adb'), shutil.which('adb')):
        if c and (os.path.isfile(c) or shutil.which(c)): return c
    sys.exit('Cannot find adb. Install the Android platform tools, or pass --adb /path/to/adb (or set ADB).')


class Phone:
    def __init__(self, adb, serial): self.adb, self.serial = adb, serial

    def run(self, *args, check=True, text=True):
        cmd = [self.adb] + (['-s', self.serial] if self.serial else []) + list(args)
        r = subprocess.run(cmd, capture_output=True, text=text)
        if check and r.returncode != 0: raise RuntimeError(f"adb {' '.join(args[:2])} failed: {(r.stderr or r.stdout).strip()[:300]}")
        return r.stdout

    def files(self):
        """{relative path: size in bytes} of every finished file in the phone's dataset folder (hidden files, such as the media library's pending ones, are skipped)."""
        out = self.run('shell', f"cd '{PHONE_DIR}' 2>/dev/null && find . -type f -exec stat -c '%s %n' {{}} +")
        res = {}
        for line in out.splitlines():
            if not line.strip(): continue
            size, path = line.strip().split(' ', 1)
            path = path[2:] if path.startswith('./') else path
            if any(part.startswith('.') for part in path.split('/')): continue
            res[path] = int(size)
        return res

    def pull(self, rel, dest):
        self.run('pull', f'{PHONE_DIR}/{rel}', dest)

    def sha256(self, rels):
        """{rel: sha256 hex} from the phone itself, in batches; empty if the phone has no sha256sum."""
        res = {}
        for i in range(0, len(rels), 40):
            batch = rels[i:i + 40]
            out = self.run('shell', "cd '%s' && sha256sum %s" % (PHONE_DIR, ' '.join("'%s'" % r for r in batch)), check=False)
            for line in out.splitlines():
                parts = line.strip().split(None, 1)
                if len(parts) == 2 and len(parts[0]) == 64: res[parts[1].lstrip('*')] = parts[0]
        return res

    def delete_scene(self, scene): self.run('shell', f"rm -rf '{PHONE_DIR}/{scene}'")


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 22), b''): h.update(chunk)
    return h.hexdigest()


def human(n): return f'{n / 1e9:.2f} GB' if n >= 1e9 else f'{n / 1e6:.0f} MB'


def main():
    ap = argparse.ArgumentParser(description='Copy the dataset off the phone, verified.')
    ap.add_argument('--dest', default=DEFAULT_DEST, help=f'where the dataset lives on this computer (default {DEFAULT_DEST})')
    ap.add_argument('--serial', help='the phone\'s adb serial, if more than one device is connected')
    ap.add_argument('--adb', help='path to adb')
    ap.add_argument('--dry-run', action='store_true', help='only say what would be copied')
    ap.add_argument('--delete-from-phone', action='store_true', help='after verifying, offer to delete the copied scenes from the phone')
    ap.add_argument('--verify-all', action='store_true', help='check every file against the phone\'s checksum, not just the ones copied this time (--delete-from-phone implies it for the scenes it would delete)')
    ap.add_argument('--yes', action='store_true', help='do not ask before deleting')
    a = ap.parse_args()

    adb = find_adb(a.adb)
    devs = [l.split()[0] for l in subprocess.run([adb, 'devices'], capture_output=True, text=True).stdout.splitlines()[1:] if l.strip().endswith('device')]
    if a.serial:
        if a.serial not in devs: sys.exit(f'Device {a.serial} is not connected (connected: {devs or "none"}). Unlock the phone and check the cable.')
    elif len(devs) != 1:
        sys.exit('No phone found. Connect it, unlock it, and accept the USB debugging prompt.' if not devs else f'More than one device ({devs}): pass --serial.')
    phone = Phone(adb, a.serial or devs[0])

    dest = os.path.abspath(os.path.expanduser(a.dest)); os.makedirs(dest, exist_ok=True)
    remote = phone.files()
    if not remote: sys.exit(f'Nothing in {PHONE_DIR} on the phone.')
    todo = [(r, s) for r, s in sorted(remote.items()) if not (os.path.isfile(os.path.join(dest, r)) and os.path.getsize(os.path.join(dest, r)) == s)]
    scenes = sorted({r.split('/')[0] for r in remote if '/' in r})
    print(f'Phone: {len(remote)} files in {len(scenes)} folders ({human(sum(remote.values()))}). Already here and the same size: {len(remote) - len(todo)}. To copy: {len(todo)} ({human(sum(s for _, s in todo))}).')
    free = shutil.disk_usage(dest).free
    if sum(s for _, s in todo) > free - 2e9: sys.exit(f'Not enough room: {human(free)} free here, {human(sum(s for _, s in todo))} needed (and 2 GB spare).')
    if a.dry_run:
        for r, s in todo[:40]: print('  would copy', r, human(s))
        if len(todo) > 40: print(f'  … and {len(todo) - 40} more')
        return

    copied, t0, done_bytes, total = [], time.time(), 0, sum(s for _, s in todo)
    for i, (rel, size) in enumerate(todo, 1):
        target = os.path.join(dest, rel); os.makedirs(os.path.dirname(target), exist_ok=True)
        part = target + '.part'
        phone.pull(rel, part)
        if os.path.getsize(part) != size: os.remove(part); sys.exit(f'{rel}: copied {os.path.getsize(part) if os.path.exists(part) else 0} bytes, the phone has {size}. Run it again.')
        os.replace(part, target); copied.append(rel); done_bytes += size
        el = time.time() - t0; eta = el / done_bytes * (total - done_bytes) if done_bytes else 0
        print(f'\r  [{i}/{len(todo)}] {human(done_bytes)} of {human(total)}  ~{eta / 60:.0f} min left   ', end='', flush=True)
    if todo: print()

    # verify against the phone's own checksums
    to_check = list(remote) if a.verify_all else copied
    bad = []
    if to_check:
        print(f'Verifying {len(to_check)} files against the phone\'s checksums…')
        theirs = phone.sha256(to_check)
        if not theirs: print('  (the phone has no sha256sum: sizes only were checked)')
        for rel in to_check:
            if rel in theirs and sha256_file(os.path.join(dest, rel)) != theirs[rel]: bad.append(rel)
        if bad:
            for rel in bad: os.remove(os.path.join(dest, rel))   # a wrong copy of the right size would be skipped next time, so it must not stay
            sys.exit('CHECKSUM MISMATCH on %d file(s); the bad copies were removed here. Run it again to copy them afresh:\n  ' % len(bad) + '\n  '.join(bad[:20]))
        print('  all match.' if theirs else '  sizes match.')

    if a.delete_from_phone:
        # a scene is deletable if it is finished (meta.json) and every one of its files is here with a verified checksum
        candidates = [s for s in scenes if s != 'PixelDoubles' and f'{s}/meta.json' in remote]
        incomplete = [s for s in scenes if s != 'PixelDoubles' and f'{s}/meta.json' not in remote]
        if incomplete: print(f'Not deleting (no meta.json, still capturing or interrupted): {incomplete}')
        mine = [r for r in remote if r.split('/')[0] in candidates or r.startswith('PixelDoubles/')]
        print(f'Checking {len(mine)} files before deleting anything…')
        theirs = phone.sha256(mine)
        if not theirs: sys.exit('The phone cannot check files (no sha256sum), so nothing will be deleted automatically. Delete by hand once you have looked at them.')
        ok_files = {r for r in mine if os.path.isfile(os.path.join(dest, r)) and r in theirs and sha256_file(os.path.join(dest, r)) == theirs[r]}
        safe = [s for s in candidates if all(r in ok_files for r in remote if r.split('/')[0] == s)]
        pix = [r for r in remote if r.startswith('PixelDoubles/')]
        pix_safe = [r for r in pix if r in ok_files]
        size = sum(remote[r] for r in remote if r.split('/')[0] in safe or r in pix_safe)
        print(f'Verified and deletable from the phone: {len(safe)} scenes and {len(pix_safe)} Pixel photos ({human(size)}).' + (f' NOT verified, kept: {len(candidates) - len(safe)} scenes, {len(pix) - len(pix_safe)} Pixel photos.' if len(safe) < len(candidates) or len(pix_safe) < len(pix) else ''))
        if (safe or pix_safe) and (a.yes or input('Delete them from the phone? They are safely here. Type "yes": ').strip().lower() == 'yes'):
            for s in safe: phone.delete_scene(s)
            for r in pix_safe: phone.run('shell', f"rm -f '{PHONE_DIR}/{r}'")
            print('Deleted from the phone.')
        else: print('Nothing deleted.')

    new = sorted({r.split('/')[0] for r in copied if r.startswith('scene_')})
    print(f'\nDone. {len(copied)} files copied ({len(new)} new scenes) into {dest}.')
    if new: print('Next: python3 tools/dataset/pair.py   then   tools/dataset/prepare.sh   (see docs/data-to-release.md)')


if __name__ == '__main__':
    try: main()
    except RuntimeError as e: sys.exit(str(e))
    except KeyboardInterrupt: sys.exit('\nStopped. Run it again to carry on: finished files are kept.')
