#!/usr/bin/env python3
"""Minimal zipalign: rewrites a zip so every STORED entry's data starts on a 4-byte boundary
(like Android's `zipalign -p 4`). Entry contents are untouched, so a v1 (JAR) signature
stays valid. Usage: zipalign.py in.apk out.apk"""
import struct
import sys

LOCAL, CENTRAL, EOCD = 0x04034b50, 0x02014b50, 0x06054b50


def align(src, dst, boundary=4):
    data = open(src, 'rb').read()
    eocd = data.rfind(struct.pack('<I', EOCD))
    count, cd_size, cd_off = struct.unpack('<HII', data[eocd + 10:eocd + 20])
    out, central = bytearray(), bytearray()
    pos = cd_off
    for _ in range(count):
        assert struct.unpack('<I', data[pos:pos + 4])[0] == CENTRAL, 'bad central record'
        method, = struct.unpack('<H', data[pos + 10:pos + 12])
        crc, csize, usize, nlen, elen, clen = struct.unpack('<IIIHHH', data[pos + 16:pos + 34])
        local_off, = struct.unpack('<I', data[pos + 42:pos + 46])
        rec_len = 46 + nlen + elen + clen
        rec = bytearray(data[pos:pos + rec_len])
        pos += rec_len

        lo = local_off
        assert struct.unpack('<I', data[lo:lo + 4])[0] == LOCAL, 'bad local header'
        lnlen, lelen = struct.unpack('<HH', data[lo + 26:lo + 30])
        name = data[lo + 30:lo + 30 + lnlen]
        body = data[lo + 30 + lnlen + lelen:lo + 30 + lnlen + lelen + csize]
        head = bytearray(data[lo:lo + 30])
        flag, = struct.unpack('<H', head[6:8])
        struct.pack_into('<H', head, 6, flag & ~0x08)          # sizes are in the header now
        struct.pack_into('<III', head, 14, crc, csize, usize)
        new_off = len(out)
        pad = 0
        if method == 0:
            pad = (boundary - (new_off + 30 + lnlen) % boundary) % boundary
        struct.pack_into('<H', head, 28, pad)
        out += head + name + b'\0' * pad + body
        struct.pack_into('<I', rec, 42, new_off)
        # Keep the central record's flags identical to the local header's. Android 8.0's
        # zip reader rejects entries whose local and central flags differ.
        cflag, = struct.unpack('<H', rec[8:10])
        struct.pack_into('<H', rec, 8, cflag & ~0x08)
        central += rec
    new_cd = len(out)
    out += central
    out += struct.pack('<IHHHHIIH', EOCD, 0, 0, count, count, len(central), new_cd, 0)
    open(dst, 'wb').write(out)


def check(path):
    """Fails if any entry's local and central flags/sizes disagree (Android 8.0 is strict)."""
    data = open(path, 'rb').read()
    eocd = data.rfind(struct.pack('<I', EOCD))
    count, cd_size, cd_off = struct.unpack('<HII', data[eocd + 10:eocd + 20])
    pos, bad = cd_off, 0
    for _ in range(count):
        cflag, = struct.unpack('<H', data[pos + 8:pos + 10])
        ccrc, ccs, cus, nlen, elen, clen = struct.unpack('<IIIHHH', data[pos + 16:pos + 34])
        lo, = struct.unpack('<I', data[pos + 42:pos + 46])
        name = data[pos + 46:pos + 46 + nlen].decode()
        lflag, = struct.unpack('<H', data[lo + 6:lo + 8])
        lcrc, lcs, lus = struct.unpack('<III', data[lo + 14:lo + 26])
        if lflag != cflag or (lcrc, lcs, lus) != (ccrc, ccs, cus):
            print('   zip mismatch:', name, hex(lflag), hex(cflag)); bad += 1
        pos += 46 + nlen + elen + clen
    if bad:
        sys.exit('   zip check FAILED: %d inconsistent entries' % bad)
    print('   zip check: %d entries consistent (Android 8.0-safe)' % count)


if __name__ == '__main__':
    if sys.argv[1] == '--check':
        check(sys.argv[2])
    else:
        align(sys.argv[1], sys.argv[2])
