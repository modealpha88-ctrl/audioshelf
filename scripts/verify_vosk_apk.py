#!/usr/bin/env python3
"""Fail CI when the native Vosk/JNA libraries are missing from an APK.

This checks packaging only: real-device execution is still needed.
"""
from pathlib import Path
from zipfile import ZipFile
import sys


def verify(path: Path) -> None:
    if not path.is_file():
        raise ValueError(f"APK missing: {path}")
    with ZipFile(path) as apk:
        contents = set(apk.namelist())
    for name in ("libvosk.so", "libjnidispatch.so"):
        entry = f"lib/arm64-v8a/{name}"
        if entry not in contents:
            raise ValueError(f"Missing {entry}: speech recognition may fail on ARM64")
        print(f"Verified {entry}")
    print(f"Both speech JNI libraries present: {path}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: verify_vosk_apk.py <apk>")
    try:
        verify(Path(sys.argv[1]))
    except (ValueError, OSError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
