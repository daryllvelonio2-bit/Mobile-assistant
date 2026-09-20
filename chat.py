#!/usr/bin/env python3
"""Interactive PC chat with Shiina (wifi-friendly, no phone needed).

Usage:
    python3 chat.py

Type a message, hit Enter. Her reply streams into the monitor.py window.
Type /quit (or Ctrl+D) to exit. Empty lines are ignored.
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from monitor import resolve_serial
from send import send_message


def main():
    ap = argparse.ArgumentParser(description="Interactive PC chat with Shiina.")
    ap.add_argument("-s", "--serial", default=None, help="adb serial (default: wifi device if present)")
    args = ap.parse_args()

    serial = resolve_serial(args.serial)
    if serial is None:
        print("No adb device found. Connect USB or run: python3 monitor.py --setup-wifi <PHONE_IP>")
        sys.exit(1)
    print(f"Chatting via {serial}. Replies appear in the monitor window. /quit to exit.")
    while True:
        try:
            line = input("you> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nbye!")
            break
        if not line:
            continue
        if line.lower() in ("/quit", "/exit", "/q"):
            print("bye!")
            break
        send_message(line, serial)


if __name__ == "__main__":
    main()
