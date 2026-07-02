#!/usr/bin/env python3
"""Rewrite UTF-8 resource string pools to UTF-16 for Android <= 2.1 (API 7).

AAPT2 (AGP 8) encodes the string pools in resources.arsc and compiled binary
XML as UTF-8. The native resource parser on Android <= 2.1 mis-reads UTF-8
string pools and throws IndexOutOfBoundsException in StringBlock.nativeGetString
the moment setContentView() touches them. Android 2.2 (API 8) fixed this.

There is no AAPT2/AGP flag to force UTF-16, so we patch the built binaries:
every ResStringPool chunk that has the UTF8 flag (0x100) set is re-encoded as
UTF-16 and the sizes of the enclosing chunks are fixed up. All references in
these formats are by string *index*, never by byte offset (except the package
header's typeStrings/keyStrings offsets, which we recompute), so this is a safe,
lossless transform: the decoded set of strings is identical before and after.

Usage: utf16_patch.py <in.arsc-or-xml> <out>   (operates on a single binary)
       it is normally driven by patch_apk(), see build-legacy.sh
"""
import struct
import sys

TYPE_STRING_POOL = 0x0001
TYPE_TABLE = 0x0002
TYPE_XML = 0x0003
TYPE_TABLE_PACKAGE = 0x0200

FLAG_UTF8 = 0x100


def _read_len(buf, pos, wide_unit):
    """Decode a string-pool length prefix.

    For UTF-8 pools the prefix unit is a byte; for UTF-16 it is a u16. A high
    bit set in the first unit means the value spans two units (big-endian-ish).
    """
    if wide_unit:
        first = struct.unpack_from('<H', buf, pos)[0]
        pos += 2
        if first & 0x8000:
            second = struct.unpack_from('<H', buf, pos)[0]
            pos += 2
            return ((first & 0x7FFF) << 16) | second, pos
        return first, pos
    else:
        first = buf[pos]
        pos += 1
        if first & 0x80:
            second = buf[pos]
            pos += 1
            return ((first & 0x7F) << 8) | second, pos
        return first, pos


def _decode_utf16_len(val):
    """Encode a UTF-16 code-unit count as a UTF-16 length prefix (bytes)."""
    if val > 0x7FFF:
        hi = ((val >> 16) | 0x8000) & 0xFFFF
        lo = val & 0xFFFF
        return struct.pack('<HH', hi, lo)
    return struct.pack('<H', val)


def _convert_string_pool(chunk):
    """Return a UTF-16 version of a ResStringPool chunk (or chunk unchanged)."""
    typ, header_size, size = struct.unpack_from('<HHI', chunk, 0)
    assert typ == TYPE_STRING_POOL
    string_count, style_count, flags, strings_start, styles_start = \
        struct.unpack_from('<IIIII', chunk, 8)

    if not (flags & FLAG_UTF8):
        return chunk  # already UTF-16, leave it alone

    # Read the per-string offset arrays.
    off_arr = 8 + 20
    str_offsets = list(struct.unpack_from('<%dI' % string_count, chunk, off_arr))
    style_off_bytes = chunk[off_arr + 4 * string_count:
                            off_arr + 4 * string_count + 4 * style_count]

    # Decode every string from the UTF-8 data region.
    strings = []
    for o in str_offsets:
        pos = strings_start + o
        _char_len, pos = _read_len(chunk, pos, wide_unit=False)   # u16 count
        byte_len, pos = _read_len(chunk, pos, wide_unit=False)    # u8 byte count
        raw = chunk[pos:pos + byte_len]
        # Resource strings are (modified) UTF-8; surrogatepass keeps any
        # lone surrogates intact for a lossless round-trip.
        strings.append(raw.decode('utf-8', 'surrogatepass'))

    # Re-encode as UTF-16 entries, tracking new offsets.
    new_data = bytearray()
    new_offsets = []
    for s in strings:
        new_offsets.append(len(new_data))
        encoded = s.encode('utf-16-le', 'surrogatepass')
        new_data += _decode_utf16_len(len(encoded) // 2)
        new_data += encoded
        new_data += b'\x00\x00'  # u16 null terminator
    while len(new_data) % 4:
        new_data += b'\x00'

    # Style data references strings by index and char position only, so it is
    # copied verbatim (just relocated). Styles are essentially never present in
    # arsc/layout pools, but handle them anyway.
    style_data = b''
    if style_count:
        style_data = bytes(chunk[styles_start:size])

    new_strings_start = 8 + 20 + 4 * string_count + 4 * style_count
    new_styles_start = (new_strings_start + len(new_data)) if style_count else 0

    out = bytearray()
    out += struct.pack('<HHI', TYPE_STRING_POOL, header_size, 0)  # size patched below
    out += struct.pack('<IIIII', string_count, style_count,
                       flags & ~FLAG_UTF8, new_strings_start, new_styles_start)
    out += struct.pack('<%dI' % string_count, *new_offsets)
    out += style_off_bytes
    out += new_data
    out += style_data
    while len(out) % 4:
        out += b'\x00'
    struct.pack_into('<I', out, 4, len(out))  # real chunk size
    return bytes(out)


def _transform(data, off, end):
    """Recursively rebuild a chunk at `off`, converting any string pools."""
    typ, header_size, size = struct.unpack_from('<HHI', data, off)
    chunk = data[off:off + size]

    if typ == TYPE_STRING_POOL:
        return _convert_string_pool(chunk)

    if typ in (TYPE_TABLE, TYPE_XML, TYPE_TABLE_PACKAGE):
        header = bytearray(chunk[:header_size])
        body = bytearray()
        child_offsets = []  # (start_in_body, type) for header fixups
        pos = header_size
        while pos < size:
            ctyp = struct.unpack_from('<H', chunk, pos)[0]
            csize = struct.unpack_from('<I', chunk, pos + 4)[0]
            child_offsets.append((len(body), ctyp))
            body += _transform(chunk, pos, pos + csize)
            pos += csize

        # ResTable_package stores byte offsets to its type/key string pools.
        if typ == TYPE_TABLE_PACKAGE:
            pool_starts = [start for (start, ct) in child_offsets
                           if ct == TYPE_STRING_POOL]
            if len(pool_starts) >= 1:
                struct.pack_into('<I', header, 268, header_size + pool_starts[0])  # typeStrings
            if len(pool_starts) >= 2:
                struct.pack_into('<I', header, 276, header_size + pool_starts[1])  # keyStrings

        out = bytearray(header)
        out += body
        struct.pack_into('<I', out, 4, len(out))
        return bytes(out)

    # Leaf chunk (table type/typespec, xml nodes, resource map, ...): verbatim.
    return chunk


def patch_bytes(data):
    typ = struct.unpack_from('<H', data, 0)[0]
    if typ not in (TYPE_TABLE, TYPE_XML):
        return data, False
    out = _transform(data, 0, len(data))
    return out, (out != data)


def _decoded_strings(data):
    """Collect all pool strings (for before/after equality verification)."""
    result = []
    i = 0
    while i < len(data) - 8:
        typ, hs, cs = struct.unpack_from('<HHI', data, i)
        if typ == TYPE_STRING_POOL and hs == 0x1C and 0x1C <= cs <= len(data) - i:
            n, sc, flags, ss, sty = struct.unpack_from('<IIIII', data, i + 8)
            offs = struct.unpack_from('<%dI' % n, data, i + 28)
            wide = not (flags & FLAG_UTF8)
            pool = []
            for o in offs:
                pos = i + ss + o
                clen, pos = _read_len(data, pos, wide)
                if wide:
                    raw = data[pos:pos + clen * 2]
                    pool.append(raw.decode('utf-16-le', 'surrogatepass'))
                else:
                    blen, pos = _read_len(data, pos, False)
                    raw = data[pos:pos + blen]
                    pool.append(raw.decode('utf-8', 'surrogatepass'))
            result.append(pool)
            i += cs
        else:
            i += 4
    return result


def _patch_entry(data):
    out, changed = patch_bytes(data)
    if changed:
        assert _decoded_strings(data) == _decoded_strings(out), \
            'string content changed during transcode -- aborting'
    return out, changed


def patch_apk(src_apk, dst_apk):
    """Copy an APK, transcoding resources.arsc and every compiled XML to UTF-16.

    Per-entry compression type is preserved so zipalign can subsequently align
    the (stored) resources.arsc. The APK must be re-signed afterwards.
    """
    import zipfile
    converted = []
    with zipfile.ZipFile(src_apk, 'r') as zin, \
            zipfile.ZipFile(dst_apk, 'w') as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            if info.filename == 'resources.arsc' or info.filename.endswith('.xml'):
                data, changed = _patch_entry(data)
                if changed:
                    converted.append(info.filename)
            # Preserve original compression (resources.arsc is stored).
            out_info = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            out_info.compress_type = info.compress_type
            out_info.external_attr = info.external_attr
            zout.writestr(out_info, data)
    print('transcoded to UTF-16: %s' % (', '.join(converted) or '(nothing)'))
    return converted


if __name__ == '__main__':
    if sys.argv[1] == '--apk':
        patch_apk(sys.argv[2], sys.argv[3])
    else:
        src, dst = sys.argv[1], sys.argv[2]
        with open(src, 'rb') as f:
            data = f.read()
        out, changed = patch_bytes(data)
        assert _decoded_strings(data) == _decoded_strings(out), \
            'string content changed during transcode -- aborting'
        with open(dst, 'wb') as f:
            f.write(out)
        print('%s: %s' % (src, 'converted' if changed else 'unchanged'))
