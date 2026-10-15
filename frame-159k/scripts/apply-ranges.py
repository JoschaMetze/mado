#!/usr/bin/env python3 -I
"""apply-ranges.py <rkdeveloptool> <ranges_dir> precheck
   apply-ranges.py <rkdeveloptool> <ranges_dir> write <id> [<id>...]
   apply-ranges.py <rkdeveloptool> <ranges_dir> rollback <id> [<id>...]
   apply-ranges.py <rkdeveloptool> <ranges_dir> verify

precheck: read every range from the device, require it to equal <id>-orig.bin.
write:    for each id, in the order given: live must equal orig (or already equal new),
          then wl new bytes, then rl and require equality with new. Stops at first problem.
rollback: same, but writes orig bytes back and verifies against orig.
verify:   read every range and report whether it equals orig or new.
"""
import sys, os, subprocess, tempfile
tool, rdir, cmd = sys.argv[1], sys.argv[2], sys.argv[3]
ids = sys.argv[4:]
ranges = {}
for line in open(os.path.join(rdir, 'ranges.txt')):
    k, s, n = line.split(); ranges[k] = (int(s), int(n))
def rk(*args):
    r = subprocess.run([tool, *args], capture_output=True, text=True)
    return r.returncode, (r.stdout + r.stderr).strip().replace('\r', '\n').splitlines()[-1:] 
def read(k):
    s, n = ranges[k]
    with tempfile.NamedTemporaryFile(suffix='.bin', delete=False) as f: p = f.name
    rc, msg = rk('rl', str(s), str(n), p)
    data = open(p, 'rb').read() if os.path.exists(p) else b''
    os.unlink(p)
    if rc != 0 or len(data) != n * 512: raise SystemExit(f"READ FAILED range {k}: rc={rc} {msg} got {len(data)} bytes")
    return data
def blobs(k):
    return open(os.path.join(rdir, f'{k}-orig.bin'), 'rb').read(), open(os.path.join(rdir, f'{k}-new.bin'), 'rb').read()
def state(k, live):
    o, n = blobs(k)
    return 'ORIG' if live == o else 'NEW' if live == n else 'OTHER'
if cmd in ('precheck', 'verify'):
    bad = 0
    for k in sorted(ranges, key=int):
        st = state(k, read(k)); s, n = ranges[k]
        print(f"range {k}: sector {s} x{n}: live == {st}")
        if cmd == 'precheck' and st != 'ORIG': bad += 1
    if bad: raise SystemExit(f"PRECHECK FAILED: {bad} range(s) differ from the backup; not safe to write")
    print("precheck OK" if cmd == 'precheck' else "verify done")
elif cmd in ('write', 'rollback'):
    for k in ids:
        s, n = ranges[k]; o, nw = blobs(k)
        target, tname = (nw, 'NEW') if cmd == 'write' else (o, 'ORIG')
        live = read(k); st = state(k, live)
        if st == 'OTHER': raise SystemExit(f"STOP: range {k} live bytes match neither orig nor new; nothing written")
        if live == target: print(f"range {k}: already {tname}, skipped"); continue
        p = os.path.join(rdir, f'{k}-{"new" if cmd=="write" else "orig"}.bin')
        rc, msg = rk('wl', str(s), p)
        print(f"range {k}: wl sector {s} x{n}: rc={rc} {msg}")
        back = read(k)
        if back != target: raise SystemExit(f"STOP: readback of range {k} does not match {tname} after write (state now {state(k, back)})")
        print(f"range {k}: readback == {tname} OK")
    print(f"{cmd} done for ranges {' '.join(ids)}")
else:
    print(__doc__)
