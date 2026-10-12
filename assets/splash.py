#!/usr/bin/env python3
"""Build Mado's boot splash for a frame. See docs/boot-splash.md before using any of it.

  splash.py bootanimation OUT.zip [--size 1280x800]
      The Android boot animation: one still frame, for /system/media/bootanimation.zip.

  splash.py logo RESOURCE.img OUT.img [--rotate 0|90|180|270] [--stock-png FILE]
      The power-on logo of a Rockchip frame. RESOURCE.img is a copy of the frame's
      `resource` partition; OUT.img is the same image with only logo.bmp replaced.

Both take --image to use a picture other than assets/splash.png, and --background for the
colour that fills the screen around it. Needs ImageMagick (`magick`) for the picture work.
"""
import argparse
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
BLOCK = 512


def magick(*args):
    subprocess.run(["magick", *map(str, args)], check=True)


def fitted(image, width, height, background, rotate=0):
    """Arguments that scale the picture to fit a width x height screen, then rotate it."""
    args = [image, "-resize", f"{width}x{height}", "-background", background,
            "-gravity", "center", "-extent", f"{width}x{height}"]
    return args + (["-rotate", rotate] if rotate else [])


def bootanimation(opts):
    width, height = (int(n) for n in opts.size.lower().split("x"))
    with tempfile.TemporaryDirectory() as tmp:
        frame = Path(tmp) / "000.png"
        magick(*fitted(opts.image, width, height, opts.background),
               "-strip", "-define", "png:color-type=2", frame)
        # Android reads the frames straight out of the archive, so nothing may be compressed.
        with zipfile.ZipFile(opts.out, "w", zipfile.ZIP_STORED) as z:
            z.writestr("desc.txt", f"{width} {height} 15\np 0 0 part0\n")
            z.write(frame, "part0/000.png")
    print(f"{opts.out}: {width}x{height}, one frame held until Android has started")


def find_logo(image):
    """Offset and size of logo.bmp inside a Rockchip resource image."""
    if image[:4] != b"RSCE":
        sys.exit("not a Rockchip resource image (it does not start with RSCE)")
    at = image.find(b"ENTR")
    while at >= 0:
        name = image[at + 4:at + 260].split(b"\0")[0]
        if name == b"logo.bmp":
            offset, size = struct.unpack_from("<II", image, at + 260)
            return offset * BLOCK, size
        at = image.find(b"ENTR", at + 4)
    sys.exit("no logo.bmp in this resource image")


def bmp_header(data):
    magic, _, _, _, pixels_at = struct.unpack_from("<2sIHHI", data)
    _, width, height, _, depth, compression = struct.unpack_from("<IiiHHI", data, 14)
    if magic != b"BM":
        sys.exit("logo.bmp is not a BMP")
    return width, height, depth, compression, pixels_at


def logo(opts):
    image = bytearray(Path(opts.resource).read_bytes())
    offset, size = find_logo(image)
    stock = bytes(image[offset:offset + size])
    width, height, depth, compression, pixels_at = bmp_header(stock)
    if compression != 0 or height < 0 or depth not in (8, 24):
        sys.exit(f"unsupported stock logo ({depth}-bit, compression {compression}, height {height}); "
                 "this only replaces plain bottom-up 8-bit and 24-bit BMPs")
    print(f"stock logo: {width}x{height}, {depth}-bit, {size} bytes at offset {offset}")

    # The picture is drawn for the screen as it stands; the logo is stored the way the panel
    # scans, so a landscape frame with a portrait panel needs it turned.
    turned = opts.rotate % 180 != 0
    screen = (height, width) if turned else (width, height)
    with tempfile.TemporaryDirectory() as tmp:
        if opts.stock_png:
            (Path(tmp) / "stock.bmp").write_bytes(stock)
            magick(Path(tmp) / "stock.bmp", "-rotate", -opts.rotate, opts.stock_png)
            print(f"stock logo saved the right way up as {opts.stock_png}")
        made = Path(tmp) / "new.bmp"
        args = fitted(opts.image, *screen, opts.background, opts.rotate)
        if depth == 8:
            colours = (pixels_at - 54) // 4
            magick(*args, "-dither", "None", "-colors", colours, "-type", "Palette",
                   "-compress", "None", f"BMP3:{made}")
        else:
            magick(*args, "-type", "TrueColor", "-compress", "None", f"BMP3:{made}")
        new = made.read_bytes()

    new_width, new_height, new_depth, _, new_pixels_at = bmp_header(new)
    if (new_width, new_height, new_depth) != (width, height, depth):
        sys.exit(f"ImageMagick wrote {new_width}x{new_height} {new_depth}-bit; expected the stock format")
    pixels = new[new_pixels_at:new_pixels_at + size - pixels_at]
    # The stock header is kept byte for byte, so the bootloader sees a file of the same
    # size and layout as the one it already knows how to draw.
    if depth == 8:
        colours = (pixels_at - 54) // 4
        if max(pixels) >= colours:
            sys.exit("the picture needs more colours than the stock palette holds")
        replacement = stock[:54] + new[54:54 + colours * 4] + pixels
    else:
        replacement = stock[:pixels_at] + pixels
    if len(replacement) != size:
        sys.exit(f"replacement is {len(replacement)} bytes, the stock logo is {size}")

    image[offset:offset + size] = replacement
    Path(opts.out).write_bytes(image)
    first = offset // BLOCK
    count = -(-(offset + size) // BLOCK) - first
    print(f"{opts.out}: only bytes {offset}..{offset + size - 1} differ from {opts.resource}")
    print("to write just those blocks, with the image copied to the frame:")
    print(f"  dd if=OUT.img of=RESOURCE_PARTITION bs={BLOCK} skip={first} seek={first} count={count}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--image", default=HERE / "splash.png", help="picture to use (default: assets/splash.png)")
    parser.add_argument("--background", default="#0e131b", help="colour around the picture")
    commands = parser.add_subparsers(dest="command", required=True)

    anim = commands.add_parser("bootanimation")
    anim.add_argument("out")
    anim.add_argument("--size", default="1280x800", help="the frame's screen, as `wm size` reports it")
    anim.set_defaults(run=bootanimation)

    res = commands.add_parser("logo")
    res.add_argument("resource")
    res.add_argument("out")
    res.add_argument("--rotate", type=int, default=0, choices=(0, 90, 180, 270),
                     help="degrees clockwise to turn the picture so it matches how the logo is stored")
    res.add_argument("--stock-png", help="also save the stock logo, turned back by --rotate; "
                                         "the right --rotate is the one that shows it upright")
    res.set_defaults(run=logo)

    opts = parser.parse_args()
    opts.run(opts)


if __name__ == "__main__":
    main()
