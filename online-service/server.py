#!/usr/bin/env python3
"""Loopback-only, authenticated, synchronous Demucs WAV separator."""
import hmac
import http.server
import os
import pathlib
import subprocess
import struct
import sys
import tempfile
import threading
import wave
import zipfile

MAX_UPLOAD = 64 * 1024 * 1024
MAX_SECONDS = 300
MAX_OUTPUT = 128 * 1024 * 1024
TIMEOUT = 900
TOKEN = os.environ.get('STEMIT_TOKEN', '')


def normalize(source, destination):
    """Accept only bounded PCM WAV and rewrite it with a known-good header."""
    try:
        with wave.open(str(source), 'rb') as inp:
            channels, width, rate, frames, compression = (inp.getnchannels(), inp.getsampwidth(),
                inp.getframerate(), inp.getnframes(), inp.getcomptype())
            if (compression != 'NONE' or channels not in (1, 2) or width != 2
                    or rate not in (44100, 48000) or frames < 1 or frames > rate * MAX_SECONDS):
                raise ValueError('Expected 16-bit mono/stereo PCM WAV, 44.1/48 kHz, at most 5 minutes')
            with wave.open(str(destination), 'wb') as out:
                out.setnchannels(channels); out.setsampwidth(width); out.setframerate(rate)
                remaining = frames
                while remaining:
                    take = min(remaining, 32768)
                    chunk = inp.readframes(take)
                    if len(chunk) != take * channels * width:
                        raise ValueError('Truncated WAV')
                    out.writeframesraw(chunk)
                    remaining -= take
    except (wave.Error, EOFError, struct.error) as exc:
        raise ValueError('Invalid WAV') from exc


def separate(work):
    """Invoke a locally installed Demucs; no URL or command is client-controlled."""
    subprocess.run([sys.executable, '-m', 'demucs.separate', '--two-stems', 'vocals',
                    '-n', 'htdemucs', '-o', str(work / 'out'), str(work / 'input.wav')],
                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                   timeout=TIMEOUT, check=True)
    folder = work / 'out' / 'htdemucs' / 'input'
    files = [folder / 'vocals.wav', folder / 'no_vocals.wav']
    if any(not f.is_file() or f.stat().st_size < 44 or f.stat().st_size > MAX_OUTPUT for f in files):
        raise RuntimeError('Missing or oversized output')
    archive = work / 'stems.zip'
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_STORED) as z:
        for path in files:
            z.write(path, path.name)
    if archive.stat().st_size > MAX_OUTPUT:
        raise RuntimeError('Oversized output')
    return archive


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def setup(self):
        super().setup()
        self.connection.settimeout(30)
    def log_message(self, format, *args):
        pass  # Do not log request paths, tokens, or audio.
    def error_response(self, status, message):
        body = (message + '\n').encode()
        self.send_response(status); self.send_header('Content-Type', 'text/plain; charset=utf-8')
        self.send_header('Content-Length', str(len(body))); self.send_header('Connection', 'close')
        self.end_headers(); self.wfile.write(body); self.close_connection = True
    def do_POST(self):
        if self.path != '/v1/separate':
            return self.error_response(404, 'Not found')
        auth = self.headers.get('Authorization', '')
        expected = 'Bearer ' + TOKEN
        if not hmac.compare_digest(auth.encode('utf-8'), expected.encode('utf-8')):
            return self.error_response(401, 'Unauthorized')
        if self.headers.get('Transfer-Encoding') or len(self.headers.get_all('Content-Length', [])) != 1:
            return self.error_response(400, 'Content-Length required; chunked uploads are unsupported')
        length = self.headers.get('Content-Length', '')
        if not length.isascii() or not length.isdigit() or len(length) > 12:
            return self.error_response(400, 'Invalid Content-Length')
        size = int(length)
        if size < 44 or size > MAX_UPLOAD:
            return self.error_response(413, 'Upload exceeds limits or is empty')
        if self.headers.get('Content-Type', '').lower() != 'audio/wav':
            return self.error_response(415, 'Use Content-Type: audio/wav')
        response_started = False
        try:
            with tempfile.TemporaryDirectory(prefix='stemit-', dir=os.environ.get('STEMIT_TEMP_DIR')) as temp:
                work = pathlib.Path(temp)
                with (work / 'upload.wav').open('wb') as out:
                    remaining = size
                    while remaining:
                        chunk = self.rfile.read(min(65536, remaining))
                        if not chunk:
                            raise ValueError('Truncated upload')
                        out.write(chunk); remaining -= len(chunk)
                normalize(work / 'upload.wav', work / 'input.wav')
                (work / 'upload.wav').unlink()
                archive = separate(work)
                response_started = True
                self.send_response(200); self.send_header('Content-Type', 'application/zip')
                self.send_header('Content-Length', str(archive.stat().st_size))
                self.send_header('Cache-Control', 'no-store'); self.send_header('Connection', 'close')
                self.end_headers()
                with archive.open('rb') as stream:
                    while True:
                        chunk = stream.read(65536)
                        if not chunk: break
                        self.wfile.write(chunk)
                self.close_connection = True
        except (ValueError, wave.Error):
            self.error_response(400, 'Invalid or unsupported WAV')
        except (subprocess.SubprocessError, OSError, RuntimeError):
            # If transmission already began, close rather than emit a second HTTP response.
            if response_started:
                self.close_connection = True
            else:
                try: self.error_response(500, 'Separation failed')
                except OSError: pass


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    def __init__(self, *args, **kwargs):
        self.slot = threading.BoundedSemaphore(1)
        super().__init__(*args, **kwargs)
    def process_request(self, request, client_address):
        if not self.slot.acquire(timeout=1):
            request.close()  # bounded workers, fail closed under load
            return
        try: super().process_request(request, client_address)
        except BaseException:
            self.slot.release(); raise
    def process_request_thread(self, request, client_address):
        try: super().process_request_thread(request, client_address)
        finally: self.slot.release()


def main():
    if len(TOKEN) < 32 or not TOKEN.isascii() or any(c.isspace() for c in TOKEN):
        sys.exit('Set STEMIT_TOKEN to at least 32 non-whitespace ASCII characters')
    port = int(os.environ.get('STEMIT_PORT', '8765'))
    server = Server(('127.0.0.1', port), Handler)
    print('Listening on loopback port', port, flush=True)
    server.serve_forever()

if __name__ == '__main__': main()
