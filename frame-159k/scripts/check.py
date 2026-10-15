#!/usr/bin/env python3 -I
"""Read-only checks for loader reads and backups. Never touches the device.

  check.py probe <file>...            summarize small reads: size, 0xCC share, magic found
  check.py backup <image> <sectors>   full verification of a raw eMMC image
  check.py compare <image> <sector> <file>   compare a re-read chunk against the image
"""
import sys, os, hashlib, gzip, struct, zlib

SECTOR = 512

def describe(b):
    cc = b.count(0xCC)
    tags = []
    if b[512:520] == b"EFI PART": tags.append("GPT header at sector 1")
    if len(b) > 0x438 + 2 and b[0x438:0x43A] == b"\x53\xef": tags.append("ext4 superblock")
    if b[:4] == b"\x3a\xff\x26\xed": tags.append("Android sparse image")
    if b[:8] == b"ANDROID!": tags.append("Android boot image")
    if b[:4] == b"KRNL" or b[:4] == b"RSCE": tags.append("Rockchip " + b[:4].decode())
    return cc, tags

def probe(files):
    for f in files:
        b = open(f, "rb").read()
        cc, tags = describe(b)
        print(f"{f}: {len(b)} bytes, 0xCC {cc}/{len(b)} ({100*cc/len(b):.1f}%), distinct bytes {len(set(b))}, {', '.join(tags) or 'no known magic'}")

def backup(path, sectors):
    expect = sectors * SECTOR
    size = os.path.getsize(path)
    print(f"size: {size} bytes, expected {expect}: {'OK' if size == expect else 'MISMATCH'}")
    h = hashlib.sha256(); cc_blocks = []; n = 0
    with open(path, "rb") as fh:
        while True:
            blk = fh.read(1 << 20)
            if not blk: break
            h.update(blk)
            if len(blk) == (1 << 20) and blk.count(0xCC) == len(blk): cc_blocks.append(n)
            n += 1
    print(f"sha256: {h.hexdigest()}")
    print(f"1 MiB blocks: {n}, blocks entirely 0xCC: {len(cc_blocks)}" + (f" (first at MiB {cc_blocks[0]})" if cc_blocks else ""))
    with open(path, "rb") as fh:
        head = fh.read(34 * SECTOR)
    cc, tags = describe(head)
    print("head:", ", ".join(tags) or "no GPT header found")
    if head[512:520] == b"EFI PART":
        hsz = struct.unpack_from("<I", head, 512 + 12)[0]
        stored = struct.unpack_from("<I", head, 512 + 16)[0]
        hdr = bytearray(head[512:512 + hsz]); hdr[16:20] = b"\0\0\0\0"
        print(f"GPT header CRC32: {'OK' if zlib.crc32(hdr) & 0xffffffff == stored else 'BAD'}")
        pe_lba, pe_n, pe_sz, pe_crc = struct.unpack_from("<QIII", head, 512 + 72)
        with open(path, "rb") as fh:
            fh.seek(pe_lba * SECTOR); pe = fh.read(pe_n * pe_sz)
        print(f"GPT entries CRC32: {'OK' if zlib.crc32(pe) & 0xffffffff == pe_crc else 'BAD'}")
        alt = struct.unpack_from("<Q", head, 512 + 32)[0]
        print(f"GPT alternate header LBA: {alt} (last sector of {sectors}-sector disk is {sectors-1}: {'OK' if alt == sectors-1 else 'DIFFERS'})")
        with open(path, "rb") as fh:
            fh.seek(alt * SECTOR); print("backup GPT signature:", "OK" if fh.read(8) == b"EFI PART" else "MISSING")

def compare(image, sector, chunk):
    b = open(chunk, "rb").read()
    with open(image, "rb") as fh:
        fh.seek(int(sector) * SECTOR); a = fh.read(len(b))
    if a == b:
        print(f"{chunk}: {len(b)} bytes at sector {sector} MATCH the image")
    else:
        diff = [i for i in range(len(b)) if a[i:i+1] != b[i:i+1]]
        print(f"{chunk}: MISMATCH, {len(diff)} bytes differ, first at offset {diff[0]} (0x{diff[0]:x})")

if __name__ == "__main__":
    a = sys.argv[1:]
    if a and a[0] == "probe": probe(a[1:])
    elif a and a[0] == "backup": backup(a[1], int(a[2]))
    elif a and a[0] == "compare": compare(a[1], a[2], a[3])
    else: print(__doc__)
