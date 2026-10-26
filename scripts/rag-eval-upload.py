#!/usr/bin/env python3
"""Upload the synthetic corpus through existing chunk+merge APIs. Does not create an ES index."""
import hashlib
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request
import uuid


def request(url, token, body, content_type):
    req = urllib.request.Request(url, body, {"Authorization": "Bearer " + token, "Content-Type": content_type}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            result = json.load(response)
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"Upload API returned HTTP {exc.code}; no response body or token logged") from None
    if result.get("code") != 200:
        raise RuntimeError("Upload API rejected request; inspect the server using its normal authorized diagnostics")
    return result


def main():
    if len(sys.argv) != 3:
        raise SystemExit("Usage: rag-eval-upload.py DATASET BASE_URL")
    root = Path(sys.argv[1]).resolve()
    manifest = json.loads((root / "dataset.json").read_text())
    base = sys.argv[2].rstrip("/")
    public_token = os.environ.get("YERAG_EVAL_TOKEN", "")
    restricted_token = os.environ.get("YERAG_EVAL_SETUP_TOKEN", "")
    if not public_token or not restricted_token:
        raise SystemExit("Set both YERAG_EVAL_TOKEN and YERAG_EVAL_SETUP_TOKEN; no files uploaded")
    documents = []
    for doc in manifest["documents"]:
        path = (root / doc["path"]).resolve()
        if not path.is_relative_to(root):
            raise ValueError("Corpus path outside dataset")
        data = path.read_bytes()
        if hashlib.md5(data).hexdigest() != doc["fileMd5"] or hashlib.sha256(data).hexdigest() != doc["sha256"]:
            raise ValueError("Corpus hash mismatch before upload")
        if doc["visibility"] not in {"public", "restricted"}:
            raise ValueError("Unknown document visibility")
        documents.append((doc, path, data))
    for doc, path, data in documents:
        token = public_token if doc["visibility"] == "public" else restricted_token
        fields = {"fileMd5": doc["fileMd5"], "chunkIndex": "0", "totalSize": str(len(data)), "fileName": path.name, "totalChunks": "1", "isPublic": str(doc["visibility"] == "public").lower()}
        if doc.get("orgTag"):
            fields["orgTag"] = doc["orgTag"]
        boundary = "yerag-" + uuid.uuid4().hex
        parts = []
        for key, value in fields.items():
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\nContent-Type: text/plain\r\n\r\n'.encode() + data + b"\r\n")
        parts.append(f"--{boundary}--\r\n".encode())
        request(base + "/api/v1/upload/chunk", token, b"".join(parts), "multipart/form-data; boundary=" + boundary)
        request(base + "/api/v1/upload/merge", token, json.dumps({"fileMd5": doc["fileMd5"], "fileName": path.name}).encode(), "application/json")
        print(doc["id"], "uploaded; parsing/indexing must be verified by live preflight")


if __name__ == "__main__":
    main()
