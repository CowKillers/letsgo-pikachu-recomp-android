# One-shot shift of the guest base 0x7000000000 -> 0x6000000000 (v0004).
# ALREADY APPLIED to this tree: re-running is a no-op (no constant in the
# 0x700... range is left). Kept as the record of how the base was moved.
import re, pathlib
root = pathlib.Path(__file__).resolve().parent / 'src' / 'runtime'
files = [
 'rt.h','fnhook.c','fnprof.c','gamebuild.c',
 'hle/fps60.c','hle/memscan.c','hle/memwatch.c','hle/movie.c','hle/wildbattle.c',
 'nvn/cmdbuf.c','nvn/objects.c','nvn/vulkan/device.c',
]
targets = [(root/f) for f in files] + [pathlib.Path(__file__).resolve().parent / 'src' / 'tools' / 'patches.py']
pat = re.compile(r'0x([0-9a-fA-F]{10,})')
def shift(m):
    v = int(m.group(0), 16)
    if 0x7000000000 <= v <= 0x7800000000:
        nv = v - 0x1000000000
        return f'0x{nv:x}'
    return m.group(0)
for p in targets:
    txt = p.read_text()
    new = pat.sub(shift, txt)
    changed = sum(1 for a, b in zip(pat.findall(txt), pat.findall(new)) if a != b)
    p.write_text(new)
    print(p, 'changed=', changed)
