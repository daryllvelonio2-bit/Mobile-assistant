#!/usr/bin/env python3
"""Send a chat message to Shiina from this PC (wifi-friendly, no phone needed).

Usage:
    python3 send.py "hello Shiina"
    python3 send.py -s 192.168.43.1:5555 "hello"

Her reply streams into the monitor.py window / logcat.
"""
import argparse
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from monitor import resolve_serial

ACTION = "com.shiina.mobile.debug.SEND_MESSAGE"


def send_message(text: str, serial: str | None = None) -> bool:
    """Send one chat message. Returns True if the broadcast was accepted."""
    text = text.strip()[:500]
    if not text:
        return False

    # adb shell re-splits args on the device: single-quote the payload
    # so multi-word messages arrive intact.
    quoted = "'" + text.replace("'", "'\\''") + "'"

    serial = serial or resolve_serial(None)
    if serial is None:
        print("No adb device found. Connect USB or run: python3 monitor.py --setup-wifi <PHONE_IP>")
        return False

    cmd = ["adb", "-s", serial, "shell", "am", "broadcast",
           "-n", "com.shiina.mobile/.debug.AdbTalkReceiver",
           "-a", ACTION, "--es", "adb_text", quoted]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
    out = (r.stdout or "") + (r.stderr or "")
    ok = "Broadcast completed" in out or "Broadcast: Intent" in out
    if ok:
        print(f"[sent via {serial}] You: {text}")
    else:
        print(out.strip() or "(no output)")
    return ok


def sync_keys(serial: str | None = None) -> bool:
    """Sync API keys from .env file to the device Keystore."""
    env_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
    if not os.path.exists(env_path):
        print("No .env file found.")
        return False
    serial = serial or resolve_serial(None)
    if serial is None:
        print("No adb device found.")
        return False
    keys = []
    with open(env_path, "r") as f:
        for line in f:
            if line.startswith("GEMINI_API_KEYS="):
                val = line.split("=", 1)[1].strip().strip('"').strip("'")
                keys = [k.strip() for k in val.split(",") if k.strip()]
    if not keys:
        print("No GEMINI_API_KEYS found in .env")
        return False
    print(f"Syncing {len(keys)} Gemini keys to {serial}...")
    for k in keys:
        cmd = ["adb", "-s", serial, "shell", "am", "broadcast",
               "-n", "com.shiina.mobile/.debug.AdbTalkReceiver",
               "-a", "com.shiina.mobile.debug.SET_KEY",
               "--es", "provider", "gemini", "--es", "key", k]
        subprocess.run(cmd, capture_output=True, timeout=10)
    print(f"Successfully synced {len(keys)} Gemini keys to {serial}!")
    return True


def main():
    ap = argparse.ArgumentParser(description="Send a chat message to Shiina over adb.")
    ap.add_argument("text", nargs="*", help="message text")
    ap.add_argument("-s", "--serial", default=None, help="adb serial (default: wifi device if present)")
    ap.add_argument("--sync-keys", action="store_true", help="sync API keys from .env to device Keystore")
    args = ap.parse_args()

    if args.sync_keys:
        if not sync_keys(args.serial):
            sys.exit(1)
        if not args.text:
            return

    if not args.text:
        ap.print_help()
        sys.exit(1)

    if not send_message(" ".join(args.text), args.serial):
        sys.exit(1)


if __name__ == "__main__":
    main()
