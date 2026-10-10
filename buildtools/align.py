"""aapt2 が出した APK に classes.dex を足し、非圧縮の中身を4バイト境界にそろえて書き直す。"""
import sys, zipfile, struct
src, dex, dst = sys.argv[1], sys.argv[2], sys.argv[3]
zin = zipfile.ZipFile(src)
items = [(i.filename, zin.read(i.filename), i.compress_type) for i in zin.infolist()]
items.append(("classes.dex", open(dex, "rb").read(), zipfile.ZIP_DEFLATED))
with open(dst, "wb") as raw:
    zout = zipfile.ZipFile(raw, "w")
    for name, data, ctype in items:
        zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
        zi.external_attr = 0o644 << 16
        zi.compress_type = ctype
        if ctype == zipfile.ZIP_STORED:
            # local header = 30 + len(name) + len(extra); data must start on a 4-byte boundary
            off = raw.tell() + 30 + len(name.encode("utf-8"))
            pad = (4 - off % 4) % 4
            zi.extra = b"\0" * pad
        zout.writestr(zi, data)
    zout.close()
# verify
z = zipfile.ZipFile(dst)
with open(dst, "rb") as f:
    for i in z.infolist():
        f.seek(i.header_offset)
        h = f.read(30)
        n, e = struct.unpack("<HH", h[26:30])
        start = i.header_offset + 30 + n + e
        if i.compress_type == zipfile.ZIP_STORED:
            assert start % 4 == 0, (i.filename, start)
        print(f"{i.filename:40s} {'stored ' if i.compress_type==0 else 'deflate'} off={start} size={i.file_size}")
