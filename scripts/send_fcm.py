#!/usr/bin/env python3
"""Send a data-only FCM message to the StepBridge device token.

Requires a Firebase Admin service account JSON (never commit it) and the
`cryptography` package:

    python3 -m pip install cryptography

Examples:

    python3 tools/send_fcm.py --sa ~/Downloads/amoo-bridge-firebase-adminsdk-xxxx.json
    python3 tools/send_fcm.py --sa "$FCM_SERVICE_ACCOUNT" --type bridge_wake
    python3 tools/send_fcm.py --sa ... --data foo=bar

The device token is read from /sdcard/bridge-amoobridge/fcm_token.txt via adb.
"""

import argparse
import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
except ImportError:
    sys.exit("missing dependency: python3 -m pip install cryptography")


def b64(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def get_access_token(sa):
    now = int(time.time())
    header = b64(json.dumps({"alg": "RS256", "typ": "JWT"}, separators=(",", ":")).encode())
    claims = b64(json.dumps({
        "iss": sa["client_email"],
        "scope": "https://www.googleapis.com/auth/firebase.messaging",
        "aud": "https://oauth2.googleapis.com/token",
        "iat": now,
        "exp": now + 3600,
    }, separators=(",", ":")).encode())
    key = serialization.load_pem_private_key(sa["private_key"].encode(), password=None)
    signature = key.sign(f"{header}.{claims}".encode(), padding.PKCS1v15(), hashes.SHA256())
    assertion = f"{header}.{claims}.{b64(signature)}"

    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion,
    }).encode()
    req = urllib.request.Request(
        "https://oauth2.googleapis.com/token",
        data=body,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)["access_token"]


def read_device_token(token_file):
    token = subprocess.check_output(["adb", "shell", "cat", token_file]).decode().strip()
    if not token:
        sys.exit(f"empty token in {token_file}; is the bridge registered?")
    return token


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sa", default=os.environ.get("FCM_SERVICE_ACCOUNT"),
                        help="Firebase Admin service account JSON (or set FCM_SERVICE_ACCOUNT)")
    parser.add_argument("--token-file", default="/sdcard/bridge-amoobridge/fcm_token.txt")
    parser.add_argument("--type", default="bridge_wake", help="value of data.type")
    parser.add_argument("--data", action="append", default=[], metavar="KEY=VALUE",
                        help="extra data payload entries (repeatable)")
    args = parser.parse_args()

    if not args.sa:
        sys.exit("missing --sa (Firebase Admin service account JSON)")
    with open(args.sa, "r", encoding="utf-8") as fh:
        sa = json.load(fh)

    token = read_device_token(args.token_file)
    print("device token:", token[:16] + "..." + token[-8:])

    data = {"type": args.type}
    for item in args.data:
        if "=" not in item:
            sys.exit(f"bad --data {item!r}, expected KEY=VALUE")
        k, v = item.split("=", 1)
        data[k] = v

    access_token = get_access_token(sa)
    message = {
        "message": {
            "token": token,
            "android": {"priority": "high"},
            "data": data,
        }
    }
    req = urllib.request.Request(
        f"https://fcm.googleapis.com/v1/projects/{sa['project_id']}/messages:send",
        data=json.dumps(message).encode(),
        headers={"Authorization": "Bearer " + access_token,
                 "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            print("HTTP", resp.status)
            print(resp.read().decode())
    except urllib.error.HTTPError as exc:
        print("HTTP", exc.code)
        print(exc.read().decode())
        sys.exit(1)


if __name__ == "__main__":
    main()
