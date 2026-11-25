# Optional online separation (prototype)

This is a separately run Python service plus a **not yet wired into the UI** Android helper. The existing on-device mode is untouched. There is no hosted endpoint, hosting account, built-in API key, or automatic fallback. Until a developer deploys the service and integrates a user-facing choice, the released app still uses only its existing local workflow.

## Privacy and consent

Online mode sends the **entire selected audio** over HTTPS to the chosen operator and returns two WAV stems. That operator, their cloud provider, reverse proxy, monitoring and backups may be able to access or retain audio, metadata, IP address, or credentials. Obtain explicit informed consent before each upload; identify the operator and their real retention policy. Do not upload copyrighted or private material without appropriate rights and permission. TLS protects transit, not against the operator. The service uses temporary files and deletes them after successful or failed requests, but abrupt termination, system crashes, proxy buffering, logs or backups can retain data. Use encrypted, quota-limited scratch storage; delete orphaned `stemit-*` directories securely under an operator retention policy. Avoid access logs and request-body capture, and never log Authorization. Android caches the result until the integrating app moves or deletes it; its cache can be evicted by Android. Update the app's privacy notice before shipping this option.

## Service installation and operation

Use a dedicated Linux host/container with Python 3.10 or 3.11, enough CPU/RAM and scratch disk (at least 1 GB, plus model and dependency space). A GPU is optional. Install and smoke-test in your environment; Demucs and its model require sizeable dependencies/downloads and inference may take several minutes. Example CPU install:

```sh
python3.11 -m venv .venv
. .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install 'numpy<2' 'torch==2.2.2' 'torchaudio==2.2.2' 'demucs==4.0.1'
# Prefetch the model from Demucs upstream before serving users:
python -c "from demucs.pretrained import get_model; get_model('htdemucs')"
# Supply a random >=32-character ASCII token from a protected secret store.
# Set STEMIT_TOKEN in the service manager's environment, not in source or a mobile APK.
# Optional: STEMIT_PORT=8765 and STEMIT_TEMP_DIR=/private/quota-limited/scratch
python online-service/server.py
```

The server binds **127.0.0.1 only**, port 8765 by default, and refuses startup without a token. It does not provide TLS. Place it behind a properly configured HTTPS reverse proxy on the same host/network namespace, with a trusted public certificate, no HTTP-to-upstream exposure, request body limit of 64 MiB, 15+ minute upstream read timeout, and a suitable upload timeout. Configure `POST /v1/separate` to proxy to `http://127.0.0.1:8765/v1/separate`, preserving Authorization and Content-Length; disable proxy request/response buffering where practical and disable access/body/auth logs. Firewall the backend. Do not expose the plain HTTP port or use cleartext Android exceptions. Configure request rate limits and per-client access controls at the edge, and rotate/revoke credentials. The shared bearer token is not a user identity, quota or abuse-prevention system; a token distributed to public clients can be extracted and reused. For a public product, implement per-user authentication, rate limits, billing/quotas and revocation via a proper gateway before release; do not embed one shared secret in the APK.

Treat uploads as untrusted. Run under an unprivileged account/container with no access to sensitive host files, resource limits (memory, CPU, processes, scratch disk and duration), restricted egress, patched dependencies and monitoring. WAV parsing, model runtimes and native libraries are not a security sandbox. A single request runs at a time; excess connections may be dropped (no queue), and slow clients can occupy that slot until the socket timeout. This prototype is not a hardened multi-tenant production service. No client-provided URL, filename, command, or model is executed/fetched. Only accepted PCM WAV is rewritten before inference. Demucs' fixed model cache may fetch weights on first use if not preloaded. Demucs upstream: https://github.com/facebookresearch/demucs (upstream says it is no longer maintained; review dependency and model licences independently before distribution/commercial use).

## Protocol

`POST https://<your-trusted-host>/v1/separate`, with `Authorization: Bearer <token>`, `Content-Type: audio/wav` and a fixed `Content-Length`. Body is **uncompressed 16-bit PCM WAV, 44.1 or 48 kHz, mono or stereo**, at most 300 seconds and 64 MiB. No MP3/M4A decoder is implemented here; Android integration must convert or clearly tell users to select a supported WAV. Success: HTTP 200 `application/zip` containing exactly `vocals.wav` and `no_vocals.wav` (instrumental), max 128 MiB. An individual stem is at most 64 MiB on the Android client. Errors use 4xx/500 without diagnostic details; busy connections can close without a response. Inference is killed after 900 seconds. The output may not perfectly preserve source quality or separate every vocal.

## Android integration (manual; not applied)

`Stemit/app/src/main/java/com/stemit/app/online/OnlineSeparator.kt` is the only app addition. No Gradle dependency, manifest change, or cleartext override is needed; INTERNET permission already exists. A developer must:

1. Deploy and verify their own HTTPS service. Supply its exact trusted URL (`https://<your-host>/v1/separate`) and a securely provisioned short-lived credential at runtime. Never accept arbitrary links from untrusted content as an endpoint, never embed a secret or disable TLS certificate/hostname checks. `OnlineSeparator` rejects HTTP, redirects, credentials in URLs, query strings and fragments. The helper cannot itself verify that a configured HTTPS host is your intended operator; enforce a trusted host allowlist in the integrating app.
2. Add a clear Local / Online choice (local default) and an explicit upload-consent dialog describing the actual operator and retention. Keep the existing local flow intact. Select supported WAV using the system picker; provide conversion separately if desired.
3. Call `OnlineSeparator(endpoint, token).separate(context, selectedContentUri)` on a background IO thread, never the UI thread. It returns `Stems(vocals, instrumental)` app-private cache files. On success, copy/move them into the app's existing library/storage workflow before cache eviction; after use delete the returned files and their parent directory. On failure display a generic message and offer retry/local processing. Connections may be dropped when busy; avoid automatic retrying large uploads without consent. Integrate lifecycle cancellation/progress and test on real devices before release. No progress or cancellation API is provided by this helper.
4. Test networking with a real trusted certificate and a provisioned credential, varied WAV inputs, slow/disconnected networks and process death. The helper never changes the source URI and cleans its scratch directory on caught failures. A process crash can leave private cache files until cleanup/eviction.

## Tests and limitations

From repository root: `python -m unittest discover -s online-service/tests -v`. The suite uses synthetic WAVs and **mocked Demucs inference**; it tests request handling, validation, command construction and archive shape, not separation quality or model/runtime compatibility. This implementation was not run end-to-end with the real Demucs model or on Android in the authoring sandbox. Validate an actual deployment and Android build/device before calling it production-ready.
