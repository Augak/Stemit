import http.client
import io
import pathlib
import tempfile
import threading
import unittest
from unittest.mock import patch
import wave
import zipfile
import sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import server


def wav(seconds=1, rate=44100, channels=1, width=2):
    buf = io.BytesIO()
    with wave.open(buf, 'wb') as f:
        f.setnchannels(channels); f.setsampwidth(width); f.setframerate(rate)
        f.writeframes(b'\0' * (rate * seconds * channels * width))
    return buf.getvalue()


class ApiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        server.TOKEN = 'A' * 40
        cls.httpd = server.Server(('127.0.0.1', 0), server.Handler)
        cls.port = cls.httpd.server_address[1]
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True); cls.thread.start()
    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown(); cls.httpd.server_close(); cls.thread.join()
    def post(self, body, headers=None, path='/v1/separate'):
        conn = http.client.HTTPConnection('127.0.0.1', self.port, timeout=10)
        default = {'Authorization': 'Bearer ' + server.TOKEN, 'Content-Type': 'audio/wav'}
        default.update(headers or {})
        conn.request('POST', path, body, default)
        response = conn.getresponse(); result = (response.status, response.read(), response.getheader('Content-Type'))
        conn.close(); return result
    def test_auth_path_and_type(self):
        self.assertEqual(self.post(wav(), {'Authorization':'Bearer wrong'})[0], 401)
        self.assertEqual(self.post(wav(), path='/other')[0], 404)
        self.assertEqual(self.post(wav(), {'Content-Type':'audio/mpeg'})[0], 415)
    def test_bad_wavs(self):
        for data in (b'x' * 100, wav(width=1), wav(rate=22050), wav()[:-9]):
            self.assertEqual(self.post(data)[0], 400)
    def test_size_limit(self):
        conn = http.client.HTTPConnection('127.0.0.1', self.port, timeout=10)
        conn.putrequest('POST', '/v1/separate'); conn.putheader('Authorization', 'Bearer ' + server.TOKEN)
        conn.putheader('Content-Type', 'audio/wav'); conn.putheader('Content-Length', str(server.MAX_UPLOAD + 1))
        conn.endheaders(); res = conn.getresponse(); self.assertEqual(res.status, 413); res.read(); conn.close()
    def test_pipeline_with_fake_inference(self):
        def fake(work):
            with wave.open(str(work / 'input.wav'), 'rb') as f:
                self.assertEqual(f.getnframes(), 44100)
            out = work / 'stems.zip'
            with zipfile.ZipFile(out, 'w') as z:
                z.writestr('vocals.wav', wav()); z.writestr('no_vocals.wav', wav())
            return out
        with patch.object(server, 'separate', fake):
            status, data, content_type = self.post(wav())
        self.assertEqual((status, content_type), (200, 'application/zip'))
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            self.assertEqual(set(z.namelist()), {'vocals.wav', 'no_vocals.wav'})
    def test_inference_failure_is_generic(self):
        with patch.object(server, 'separate', side_effect=RuntimeError('secret')):
            status, body, _ = self.post(wav())
        self.assertEqual(status, 500); self.assertNotIn(b'secret', body)
    def test_demucs_command_and_archive(self):
        with tempfile.TemporaryDirectory() as temp:
            work = pathlib.Path(temp)
            def fake_run(command, **kwargs):
                self.assertEqual(command[1:7], ['-m','demucs.separate','--two-stems','vocals','-n','htdemucs'])
                self.assertTrue(kwargs['check']); self.assertEqual(kwargs['timeout'], server.TIMEOUT)
                target = work / 'out' / 'htdemucs' / 'input'; target.mkdir(parents=True)
                for name in ('vocals.wav', 'no_vocals.wav'): (target / name).write_bytes(wav())
            with patch.object(server.subprocess, 'run', fake_run): archive = server.separate(work)
            with zipfile.ZipFile(archive) as z: self.assertEqual(set(z.namelist()), {'vocals.wav','no_vocals.wav'})

if __name__ == '__main__': unittest.main()
