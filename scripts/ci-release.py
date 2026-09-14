#!/usr/bin/env python3
"""Local, credential-free release checks plus ephemeral CI signing preparation."""

import argparse
import base64
import binascii
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
UPLOAD_CERT_SHA256 = (
    "0A:AB:51:C0:4B:D6:A5:13:EC:67:52:59:15:B7:8A:30:"
    "AA:78:E6:E9:55:E3:C5:B3:A8:58:FB:99:80:33:9E:7B"
).replace(":", "")
INTERNAL_COPY = re.compile(
    r"\b(?:test(?:ing)?|debug|fixture|override|placeholder)\b|\binternal[\s-]+track\b",
    re.IGNORECASE,
)


def version_code(run_number, base):
    if not re.fullmatch(r"[1-9][0-9]*", run_number):
        raise ValueError("GITHUB_RUN_NUMBER must be a positive integer.")
    if not re.fullmatch(r"[0-9]+", base):
        raise ValueError("PLAY_VERSION_CODE_BASE must be a nonnegative integer.")
    code = int(base) + int(run_number)
    if not 1 <= code <= 2100000000:
        raise ValueError("Computed version code exceeds the Google Play limit.")
    return code


def audit_copy(root):
    failures = []
    for path in sorted((root / "app/src/main/res").glob("values*/*.xml")):
        for resource in ET.parse(path).getroot():
            if resource.tag not in ("string", "plurals", "string-array"):
                continue
            # Inspect visible text, not resource names such as blocker_request_override_title.
            if INTERNAL_COPY.search(" ".join(resource.itertext())):
                failures.append(f"{path.relative_to(root)}: {resource.get('name')}")
    for path in [root / "play-store/listing-copy.txt", *sorted(
            (root / "play-store/whatsnew").glob("whatsnew-*"))]:
        text = path.read_text(encoding="utf-8").strip()
        if not text or INTERNAL_COPY.search(text):
            failures.append(str(path.relative_to(root)))
        if path.name.startswith("whatsnew-") and len(text) > 500:
            failures.append(f"{path.relative_to(root)}: exceeds 500 characters")
    if not (root / "play-store/whatsnew/whatsnew-en-US").is_file():
        failures.append("Missing English release notes.")
    if failures:
        raise ValueError("Release copy audit failed: " + ", ".join(failures))


def prepare_signing(destination):
    required = ("AIRLOCK_KEYSTORE_BASE64", "AIRLOCK_KEYSTORE_PASSWORD",
                "AIRLOCK_KEY_ALIAS", "AIRLOCK_KEY_PASSWORD", "GOOGLE_PLAY_SERVICE_ACCOUNT_JSON")
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        raise ValueError("Configure GitHub environment secrets: " + ", ".join(missing))
    try:
        data = base64.b64decode("".join(os.environ["AIRLOCK_KEYSTORE_BASE64"].split()), validate=True)
    except (ValueError, binascii.Error):
        raise ValueError("AIRLOCK_KEYSTORE_BASE64 is not valid base64.") from None
    if not data:
        raise ValueError("The upload keystore is empty.")
    # Never overwrite a pre-existing signing file, including a local developer's key.
    with destination.open("xb") as stream:
        destination.chmod(0o600)
        stream.write(data)
    result = subprocess.run([
        "keytool", "-exportcert", "-keystore", str(destination),
        "-alias", os.environ["AIRLOCK_KEY_ALIAS"],
        "-storepass:env", "AIRLOCK_KEYSTORE_PASSWORD",
    ], capture_output=True, check=False)
    if result.returncode:
        raise ValueError("Cannot open the upload key; check keystore, alias, and password secrets.")
    import hashlib
    if hashlib.sha256(result.stdout).hexdigest().upper() != UPLOAD_CERT_SHA256:
        raise ValueError("Upload certificate differs from Airlock's existing signing identity.")


def verify_bundle(bundle):
    if not bundle.is_file():
        raise ValueError("Release bundle is missing.")
    result = subprocess.run([
        "jarsigner", "-J-Duser.language=en", "-J-Duser.country=US", "-verify", "-strict", str(bundle)
    ], capture_output=True, text=True, check=False)
    # Exit bit 4 is expected for the existing self-signed certificate. Other bits
    # include unsigned entries (16); these must never pass the publishing gate.
    if result.returncode not in (0, 4) or "jar verified" not in result.stdout:
        raise ValueError("Release bundle signature verification failed.")
    result = subprocess.run([
        "keytool", "-J-Duser.language=en", "-J-Duser.country=US", "-printcert", "-jarfile", str(bundle)
    ], capture_output=True, text=True, check=False)
    fingerprints = re.findall(r"SHA256:\s*([0-9A-F:]+)", result.stdout)
    if result.returncode or not fingerprints or any(
            value.replace(":", "") != UPLOAD_CERT_SHA256 for value in fingerprints):
        raise ValueError("Release bundle does not use the existing upload certificate.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("version", "audit", "prepare-signing", "verify-bundle"))
    parser.add_argument("--path", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "version":
            print(version_code(os.environ.get("GITHUB_RUN_NUMBER", ""),
                               os.environ.get("PLAY_VERSION_CODE_BASE", "10000")))
        elif args.command == "audit":
            audit_copy(ROOT)
            print("Release text audit passed. Screenshots still require visual review.")
        elif args.command == "prepare-signing":
            if not args.path:
                parser.error("prepare-signing requires --path")
            prepare_signing(args.path)
            print("Existing upload certificate verified.")
        else:
            verify_bundle(args.path or ROOT / "app/build/outputs/bundle/release/app-release.aab")
            print("Release bundle signature and upload certificate verified.")
    except (ValueError, OSError, ET.ParseError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
