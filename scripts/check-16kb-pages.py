#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Owner brief H4 item 9: every native library (.so) in the APKs works on 16 KB-page phones.

Android 15+ phones may use 16 KB memory pages, and Google Play requires it for apps that ship native code. A .so
passes when each of its LOAD segments is aligned to at least 16 KB and, when it is stored uncompressed, its data
starts at a 16 KB boundary inside the APK. KeepClear has no native code of its own (Compose brings libandroidx.graphics.path.so, which is
aligned); this check makes sure no dependency brings an unaligned one in unnoticed. With no .so at all it says so and passes.

Usage: check-16kb-pages.py APK [APK ...]      (exit 1 on any failure)
       check-16kb-pages.py --self-test
"""
import struct
import sys
import zipfile

PAGE = 16 * 1024
PT_LOAD = 1


def load_alignments(elf: bytes):
    """The p_align of every LOAD segment of an ELF file (32- or 64-bit, little or big endian)."""
    if elf[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    is64 = elf[4] == 2
    end = "<" if elf[5] == 1 else ">"
    if is64:
        phoff, = struct.unpack_from(end + "Q", elf, 0x20)
        phentsize, phnum = struct.unpack_from(end + "HH", elf, 0x36)
    else:
        phoff, = struct.unpack_from(end + "I", elf, 0x1C)
        phentsize, phnum = struct.unpack_from(end + "HH", elf, 0x2A)
    out = []
    for i in range(phnum):
        base = phoff + i * phentsize
        p_type, = struct.unpack_from(end + "I", elf, base)
        if p_type != PT_LOAD:
            continue
        if is64:
            p_align, = struct.unpack_from(end + "Q", elf, base + 0x30)
        else:
            p_align, = struct.unpack_from(end + "I", elf, base + 0x1C)
        out.append(p_align)
    return out


def data_offset(apk: zipfile.ZipFile, info: zipfile.ZipInfo) -> int:
    """Where the entry's bytes start in the APK (after its local file header)."""
    apk.fp.seek(info.header_offset)
    header = apk.fp.read(30)
    name_len, extra_len = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_len + extra_len


def check_apk(path: str):
    problems, libs = [], 0
    with zipfile.ZipFile(path) as apk:
        for info in apk.infolist():
            if not (info.filename.startswith("lib/") and info.filename.endswith(".so")):
                continue
            libs += 1
            aligns = load_alignments(apk.read(info))
            small = [a for a in aligns if a < PAGE]
            if small:
                problems.append(f"{path}: {info.filename}: LOAD segments aligned to {sorted(set(small))}, need >= {PAGE}")
            if info.compress_type == zipfile.ZIP_STORED and data_offset(apk, info) % PAGE != 0:
                problems.append(f"{path}: {info.filename}: stored uncompressed but not at a 16 KB boundary")
    return libs, problems


def _elf64(align: int) -> bytes:
    """A minimal 64-bit little-endian ELF with one LOAD segment (for --self-test)."""
    header = bytearray(64)
    header[:4] = b"\x7fELF"
    header[4], header[5] = 2, 1
    struct.pack_into("<Q", header, 0x20, 64)
    struct.pack_into("<HH", header, 0x36, 56, 1)
    ph = bytearray(56)
    struct.pack_into("<I", ph, 0, PT_LOAD)
    struct.pack_into("<Q", ph, 0x30, align)
    return bytes(header + ph)


def self_test():
    assert load_alignments(_elf64(0x4000)) == [0x4000]
    assert load_alignments(_elf64(0x1000)) == [0x1000]
    try:
        load_alignments(b"nope")
        raise AssertionError("a non-ELF file was accepted")
    except ValueError:
        pass
    print("self-test passed")


def main(argv):
    if argv == ["--self-test"]:
        self_test()
        return 0
    if not argv:
        print(__doc__)
        return 2
    total, problems = 0, []
    for path in argv:
        libs, found = check_apk(path)
        total += libs
        problems += found
        print(f"{path}: {libs} native librar{'y' if libs == 1 else 'ies'}")
    if total == 0:
        print("No native libraries in the APKs: nothing to align (16 KB pages are fine).")
    for p in problems:
        print("FAIL", p)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
