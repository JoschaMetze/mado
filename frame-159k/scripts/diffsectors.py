#!/usr/bin/env python3 -I
"""diffsectors.py <original> <edited> <start_sector> <sector_count> [out_dir]
Compare two images over a sector range; print coalesced changed 512-byte ranges.
With out_dir, write <n>-orig.bin and <n>-new.bin per range plus ranges.txt."""
import sys, os
orig, new, start, count = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
out = sys.argv[5] if len(sys.argv) > 5 else None
S = 512; CH = 1 << 20
ranges = []
with open(orig,'rb') as a, open(new,'rb') as b:
    a.seek(start*S); b.seek(start*S)
    left = count*S; pos = start
    while left > 0:
        n = min(CH, left); x = a.read(n); y = b.read(n)
        assert len(x) == n and len(y) == n, "short read"
        if x != y:
            for i in range(0, n, S):
                if x[i:i+S] != y[i:i+S]:
                    sec = pos + i//S
                    if ranges and ranges[-1][1] == sec: ranges[-1][1] = sec+1
                    else: ranges.append([sec, sec+1])
        pos += n//S; left -= n
tot = sum(e-s for s,e in ranges)
print(f"{len(ranges)} changed ranges, {tot} sectors, {tot*S} bytes")
if out:
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out,'ranges.txt'),'w') as f:
        for k,(s,e) in enumerate(ranges):
            f.write(f"{k} {s} {e-s}\n")
            with open(orig,'rb') as a: a.seek(s*S); open(os.path.join(out,f"{k}-orig.bin"),'wb').write(a.read((e-s)*S))
            with open(new,'rb') as b: b.seek(s*S); open(os.path.join(out,f"{k}-new.bin"),'wb').write(b.read((e-s)*S))
for k,(s,e) in enumerate(ranges): print(f"  range {k}: sector {s} ({(s-start)*S:#x} into partition), {e-s} sectors")
