import io
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

pkg = 'com.mohamed.audioshelf'
out = Path('/tmp/audioshelf-screenshots')
out.mkdir(exist_ok=True)
data = Path('/tmp/shelf-fixtures')
art = Path('app/src/main/res/drawable-nodpi')

def adb(*args, **kwargs):
    return subprocess.run(['adb', *args], check=True, **kwargs)

def put(local, remote):
    adb('shell', 'run-as', pkg, 'sh', '-c', f"'cat > {remote}'", stdin=local.open('rb'))

def tap(x, y):
    adb('shell', 'input', 'tap', str(x), str(y))

def shot(name, seconds=3):
    time.sleep(seconds)
    result = adb('exec-out', 'screencap', '-p', capture_output=True).stdout
    if not result.startswith(b'\x89PNG'):
        raise RuntimeError(f'Invalid screenshot: {name}')
    (out / (name + '.png')).write_bytes(result)

def launch(count, motion=True, width=720, height=1600):
    adb('shell', 'am', 'force-stop', pkg)
    put(data / f'library-{count}.xml', 'shared_prefs/audio_shelf.xml')
    put(data / f'visual-{str(motion).lower()}.xml', 'shared_prefs/audio_shelf_library_visuals.xml')
    adb('shell', 'am', 'start', '-W', '-n', pkg + '/.MainActivity')
    time.sleep(6)
    tap(width // 2, height - 150)

adb('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk')
adb('shell', 'pm', 'grant', pkg, 'android.permission.POST_NOTIFICATIONS')
adb('shell', 'run-as', pkg, 'mkdir', '-p', 'shared_prefs', 'files/covers')
put(art / 'lounge_frankenstein_print.webp', 'files/covers/0.webp')
put(art / 'lounge_dracula_print.webp', 'files/covers/1.webp')
adb('shell', 'cmd', 'uimode', 'night', 'yes')
adb('shell', 'wm', 'size', '720x1600')
adb('shell', 'wm', 'density', '320')
for count in (0, 1, 2, 9):
    launch(count)
    shot(f'narrow-dark-{count}')
    if count == 2:
        tap(180, 875)
        shot('collector-detail', seconds=5)
        tap(182, 810)
        shot('story-aurora', seconds=5)
        time.sleep(4)
        shot('story-aurora-later')
    if count == 9:
        adb('shell', 'dumpsys', 'gfxinfo', pkg, 'reset', stdout=subprocess.DEVNULL)
        adb('shell', 'input', 'swipe', '350', '1370', '350', '410', '600')
        time.sleep(3)
        shot('narrow-dark-9-scrolled')
        adb('shell', 'dumpsys', 'gfxinfo', pkg, 'framestats',
            stdout=(out / 'frame-stats.txt').open('wb'))
adb('shell', 'cmd', 'uimode', 'night', 'no')
adb('shell', 'wm', 'size', '1000x1850')
for scale in ('animator_duration_scale', 'window_animation_scale', 'transition_animation_scale'):
    adb('shell', 'settings', 'put', 'global', scale, '0')
launch(9, False, width=1000, height=1850)
shot('wide-light-9-reduced-motion', seconds=5)

tap(240, 875)
time.sleep(5)
tap(182, 810)
shot('story-aurora-reduced-motion', seconds=5)
time.sleep(5)
shot('story-aurora-reduced-motion-later')
