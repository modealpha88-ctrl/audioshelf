import json, sys, wave
from pathlib import Path
from xml.etree.ElementTree import Element, SubElement, tostring
root = Path('/tmp/shelf-fixtures')
root.mkdir(exist_ok=True)
app = '/data/user/0/com.mohamed.audioshelf/files'
for count in (0, 1, 2, 9):
    books = [
        dict(id=f'content://media/external/audio/media/{i+1}', sourceId='local_files',
             title=('Frankenstein', 'Dracula', 'Unillustrated book')[i % 3],
             authors=('Mary Shelley', 'Bram Stoker', 'AudioShelf')[i % 3],
             coverUrl=(f'file://{app}/covers/{i % 3}.webp' if i % 3 < 2 else ''))
        for i in range(count)
    ]
    p = Element('map')
    SubElement(p, 'string', name='library_books').text = json.dumps(books)
    SubElement(p, 'string', name='listening_progress').text = json.dumps({
        'local_files:' + b['id']: dict(chapter=0, position=18500, chapterCount=1,
                                      chapterDurationMs=60000) for b in books[:2]
    })
    (root / f'library-{count}.xml').write_bytes(tostring(p, encoding='utf-8', xml_declaration=True))
for enabled in ('true', 'false'):
    p = Element('map')
    SubElement(p, 'string', name='visual_style').text = 'Walnut'
    SubElement(p, 'boolean', name='motion_enabled', value=enabled)
    (root / f'visual-{enabled}.xml').write_bytes(tostring(p, encoding='utf-8', xml_declaration=True))
with wave.open(str(root / 'test-tone.wav'), 'wb') as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(8000)
    w.writeframes(b'\0' * 960000)
