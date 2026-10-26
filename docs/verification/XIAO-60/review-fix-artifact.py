"""Preserve the original new JAR byte stream; reuse only hash-verified unchanged STORED libraries.

pack NEW_JAR BUNDLE / restore BUNDLE OLD_OR_REBUILT_JAR OUTPUT_JAR
This does not repackage the target JAR or infer that the base archive was executed.
"""
import hashlib
import io
import json
from pathlib import Path
import struct
import sys
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def pack(jar, bundle):
    raw = jar.read_bytes()
    chunks = []
    literals = bytearray()
    cursor = 0
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        for entry in sorted(archive.infolist(), key=lambda value: value.header_offset):
            if not entry.filename.startswith('BOOT-INF/lib/') or not entry.filename.endswith('.jar'):
                continue
            assert entry.compress_type == zipfile.ZIP_STORED
            name_length, extra_length = struct.unpack_from('<HH', raw, entry.header_offset + 26)
            start = entry.header_offset + 30 + name_length + extra_length
            content = archive.read(entry)
            assert raw[start:start + entry.file_size] == content
            segment = raw[cursor:start]
            chunks.append({'literalOffset': len(literals), 'bytes': len(segment)})
            literals.extend(segment)
            chunks.append({'library': entry.filename, 'bytes': len(content), 'sha256': sha(content)})
            cursor = start + entry.file_size
    tail = raw[cursor:]
    chunks.append({'literalOffset': len(literals), 'bytes': len(tail)})
    literals.extend(tail)
    layout = {'jarBytes': len(raw), 'jarSHA256': sha(raw), 'chunks': chunks,
              'boundary': 'Original target archive bytes, not a rebuilt ZIP. Each reused library is verified independently.'}
    with zipfile.ZipFile(bundle, 'w', compression=zipfile.ZIP_DEFLATED) as out:
        out.writestr('layout.json', json.dumps(layout, indent=2))
        out.writestr('literal.bin', literals)
    return {'jarBytes': len(raw), 'jarSHA256': sha(raw), 'bundleBytes': bundle.stat().st_size,
            'bundleSHA256': sha(bundle.read_bytes()), 'libraries': sum('library' in c for c in chunks)}


def restore(bundle, base, output):
    with zipfile.ZipFile(bundle) as archive:
        layout = json.loads(archive.read('layout.json'))
        literal = archive.read('literal.bin')
    result = bytearray()
    with zipfile.ZipFile(base) as archive:
        for chunk in layout['chunks']:
            if 'library' in chunk:
                content = archive.read(chunk['library'])
                assert len(content) == chunk['bytes'] and sha(content) == chunk['sha256'], chunk['library']
            else:
                content = literal[chunk['literalOffset']:chunk['literalOffset'] + chunk['bytes']]
            result.extend(content)
    assert len(result) == layout['jarBytes'] and sha(result) == layout['jarSHA256']
    output.write_bytes(result)
    return {'jarBytes': len(result), 'jarSHA256': sha(result), 'baseBytes': base.stat().st_size,
            'baseSHA256': sha(base.read_bytes()), 'originalArchiveRestored': True}


if __name__ == '__main__':
    if sys.argv[1] == 'pack':
        result = pack(Path(sys.argv[2]), Path(sys.argv[3]))
    elif sys.argv[1] == 'restore':
        result = restore(Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]))
    else:
        raise ValueError('pack or restore required')
    print(json.dumps(result, indent=2))
