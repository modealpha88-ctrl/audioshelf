#!/usr/bin/env python3
"""Fail Android CI if libtorrent's native runtime is missing from an APK."""
from pathlib import Path
import sys
from zipfile import ZipFile

apk = Path(sys.argv[1]) if len(sys.argv) > 1 else None
if apk is None or not apk.is_file():
    raise SystemExit("Usage: verify_torrent_apk.py path/to/app.apk")

with ZipFile(apk) as archive:
    entries = set(archive.namelist())
    abis = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
    missing = [f"lib/{abi}/libjlibtorrent.so" for abi in abis
               if f"lib/{abi}/libjlibtorrent.so" not in entries]
if missing:
    raise SystemExit("Native BitTorrent engine missing from APK: " + ", ".join(missing))
print("Verified libjlibtorrent.so for all four supported Android ABIs.")
