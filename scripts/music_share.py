#!/usr/bin/env python3
"""Prepare a private music bundle, or serve only its explicitly listed audio files."""
import argparse
import hashlib
import json
import mimetypes
import os
from pathlib import Path
import secrets
import shutil
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, quote
from urllib.request import urlopen

AUDIO_EXTENSIONS = {'.mp3', '.flac', '.m4a', '.wav', '.aac', '.ogg', '.opus', '.amr', '.aiff', '.aif'}


def prepare(args):
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    state_path = out / 'server-state.json'
    token = json.loads(state_path.read_text())['token'] if state_path.exists() else secrets.token_urlsafe(24)
    base = 'http://{}:{}/s/{}'.format(args.host, args.port, token)
    unique = {}
    source_count = 0
    for root in args.directories:
        root = Path(root).resolve(strict=True)
        for file in sorted(root.rglob('*')):
            if not file.is_file() or file.is_symlink() or file.suffix.lower() not in AUDIO_EXTENSIONS:
                continue
            # Do not follow directory symlinks out of the explicitly shared root.
            resolved = file.resolve(strict=True)
            if root not in resolved.parents:
                continue
            stat = file.stat()
            if not 0 < stat.st_size <= 512 * 1024 * 1024:
                raise ValueError('Audio file is empty or exceeds 512 MB: {}'.format(file))
            digest = hashlib.sha256()
            with file.open('rb') as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(block)
            if (file.stat().st_size, file.stat().st_mtime_ns) != (stat.st_size, stat.st_mtime_ns):
                raise ValueError('Audio changed while preparing: {}'.format(file))
            sha = digest.hexdigest()
            source_count += 1
            if sha not in unique:
                audio_dir = out / 'audio'
                audio_dir.mkdir(exist_ok=True)
                exported = audio_dir / (sha + file.suffix.lower())
                if not exported.exists():
                    temporary = exported.with_suffix('.part')
                    shutil.copyfile(file, temporary)
                    temporary.replace(exported)
                export_stat = exported.stat()
                unique[sha] = dict(path=str(exported), title=file.stem, size=stat.st_size,
                                   sha256=sha, mtime_ns=export_stat.st_mtime_ns)
    if not unique or len(unique) > 1000:
        raise ValueError('A bundle must contain 1 to 1000 unique audio files')
    tracks = [dict(title=t['title'], size=t['size'], sha256=t['sha256'],
                   url=base + '/audio/' + t['sha256']) for t in unique.values()]
    manifest = dict(format='personal-music-bundle', version=1, title=args.title,
                    sourceCount=source_count, tracks=tracks)
    encoded = json.dumps(manifest, ensure_ascii=False, separators=(',', ':')).encode()
    if len(encoded) > 1024 * 1024:
        raise ValueError('Manifest exceeds 1 MB')
    state = dict(token=token, host=args.host, port=args.port, manifest=manifest, files=unique)
    state_path.write_text(json.dumps(state, ensure_ascii=False, indent=2))
    os.chmod(state_path, 0o600)
    (out / 'collection.music.json').write_bytes(encoded)
    url = base + '/collection.music.json'
    (out / 'music-url.txt').write_text(url + '\n')
    try:
        import qrcode
        qrcode.make(url).save(out / 'music-qr.png')
    except ImportError:
        # Reuse the existing local APK Hub QR encoder; no music leaves this Mac.
        with urlopen('http://127.0.0.1:18780/api/v1/qr?text=' + quote(url, safe=''), timeout=15) as response:
            png = response.read(2 * 1024 * 1024)
        if not png.startswith(b'\x89PNG\r\n\x1a\n'):
            raise ValueError('Local QR encoder did not return a PNG')
        (out / 'music-qr.png').write_bytes(png)
    print(json.dumps(dict(source_count=source_count, tracks=len(tracks),
                          total_bytes=sum(t['size'] for t in tracks), url=url,
                          qr=str(out / 'music-qr.png')), ensure_ascii=False))


def serve(args):
    state = json.loads(Path(args.state).read_text())
    prefix = '/s/' + state['token']
    manifest = json.dumps(state['manifest'], ensure_ascii=False, separators=(',', ':')).encode()

    class Handler(BaseHTTPRequestHandler):
        def do_HEAD(self):
            self.send_file(head=True)

        def do_GET(self):
            self.send_file(head=False)

        def send_file(self, head):
            path = urlsplit(self.path).path
            if path == prefix + '/collection.music.json':
                self.send_response(200)
                self.send_header('Content-Type', 'application/json; charset=utf-8')
                self.send_header('Content-Length', str(len(manifest)))
                self.send_header('Cache-Control', 'no-store')
                self.end_headers()
                if not head:
                    self.wfile.write(manifest)
                return
            key = path[len(prefix + '/audio/'):] if path.startswith(prefix + '/audio/') else ''
            record = state['files'].get(key)
            if not record:
                self.send_error(404)
                return
            file = Path(record['path'])
            try:
                stat = file.stat()
                if file.is_symlink() or str(file.resolve()) != record['path'] or (stat.st_size, stat.st_mtime_ns) != (record['size'], record['mtime_ns']):
                    self.send_error(409, 'Audio changed; regenerate the music bundle')
                    return
                with file.open('rb') as stream:
                    self.send_response(200)
                    self.send_header('Content-Type', mimetypes.guess_type(str(file))[0] or 'application/octet-stream')
                    self.send_header('Content-Length', str(stat.st_size))
                    self.send_header('Cache-Control', 'no-store')
                    self.end_headers()
                    if not head:
                        self.connection.settimeout(60)
                        for block in iter(lambda: stream.read(256 * 1024), b''):
                            self.wfile.write(block)
            except (BrokenPipeError, ConnectionResetError, TimeoutError):
                pass
            except FileNotFoundError:
                self.send_error(404)
            except PermissionError:
                self.send_error(403)

        def log_message(self, fmt, *values):
            # Avoid retaining the private share token in access logs.
            pass

    server = ThreadingHTTPServer((args.bind, state['port']), Handler)
    server.daemon_threads = True
    print('Music share is listening on port {} ({} tracks)'.format(state['port'], len(state['files'])), flush=True)
    server.serve_forever()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    create = sub.add_parser('prepare')
    create.add_argument('--directories', nargs='+', required=True)
    create.add_argument('--host', required=True)
    create.add_argument('--port', type=int, default=18781)
    create.add_argument('--title', default='我的音乐合集')
    create.add_argument('--out', required=True)
    start = sub.add_parser('serve')
    start.add_argument('--state', required=True)
    start.add_argument('--bind', default='0.0.0.0')
    args = parser.parse_args()
    prepare(args) if args.command == 'prepare' else serve(args)
