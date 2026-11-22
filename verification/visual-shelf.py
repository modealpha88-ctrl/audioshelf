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

def tap(label, timeout=40):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        result = adb('shell', 'uiautomator', 'dump', '/sdcard/shelf-window.xml', capture_output=True)
        if b'dumped' in result.stdout:
            xml = adb('exec-out', 'cat', '/sdcard/shelf-window.xml', capture_output=True).stdout
            for node in ET.fromstring(xml).iter('node'):
                values = (node.get('text', ''), node.get('content-desc', ''))
                if any(v.lower() == label.lower() or v.startswith(label + ',') for v in values):
                    x1,y1,x2,y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
                    if x2 > x1 and y2 > y1:
                        adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                        return
        time.sleep(1)
    raise RuntimeError(f'Target not visible: {label}')

def shot(name):
    time.sleep(1)
    result = adb('exec-out', 'screencap', '-p', capture_output=True).stdout
    if not result.startswith(b'\x89PNG'):
        raise RuntimeError(f'Invalid screenshot: {name}')
    (out / (name + '.png')).write_bytes(result)

def launch(count, motion=True):
    adb('shell', 'am', 'force-stop', pkg)
    put(data / f'library-{count}.xml', 'shared_prefs/audio_shelf.xml')
    put(data / f'visual-{str(motion).lower()}.xml', 'shared_prefs/audio_shelf_library_visuals.xml')
    adb('shell', 'am', 'start', '-W', '-n', pkg + '/.MainActivity')
    tap('Library')

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
        tap('Frankenstein')
        shot('collector-detail')
        tap('Story mode')
        shot('story-aurora')
        time.sleep(4)
        shot('story-aurora-later')
    if count == 9:
        adb('shell', 'input', 'swipe', '350', '1250', '350', '540', '380')
        shot('narrow-dark-9-scrolled')
        adb('shell', 'dumpsys', 'gfxinfo', pkg, 'framestats',
            stdout=(out / 'frame-stats.txt').open('wb'))
adb('shell', 'cmd', 'uimode', 'night', 'no')
adb('shell', 'wm', 'size', '1000x1850')
for scale in ('animator_duration_scale', 'window_animation_scale', 'transition_animation_scale'):
    adb('shell', 'settings', 'put', 'global', scale, '0')
launch(9, False)
shot('wide-light-9-reduced-motion')
