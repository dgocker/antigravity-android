#!/usr/bin/env python3
import sys
import os

from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))

from app.db import init_db, create_device_token, list_device_tokens, revoke_device_token

def main():
    init_db()
    if len(sys.argv) < 2:
        print("Usage:")
        print("  agy-token.py create <device_name>")
        print("  agy-token.py list")
        print("  agy-token.py revoke <token_id>")
        sys.exit(1)

    cmd = sys.argv[1].lower()
    if cmd in ("create", "new"):
        name = sys.argv[2] if len(sys.argv) > 2 else "Android Device"
        token_id, raw_token, created_at = create_device_token(name)
        print(f"Device: {name} (ID: {token_id})")
        print("Token:")
        print(raw_token)
        print(f"Created: {created_at}")
        print("NOTE: Store this token in your Android app. The raw token is not stored on the server.")
    elif cmd in ("list", "ls"):
        tokens = list_device_tokens()
        if not tokens:
            print("No device tokens registered.")
            return
        print(f"{'ID':<4} {'DEVICE NAME':<25} {'CREATED':<25} {'LAST USED':<25} {'STATUS'}")
        print("-" * 90)
        for t in tokens:
            status = "REVOKED" if t["revoked_at"] else "ACTIVE"
            last_used = t["last_used_at"] or "never"
            print(f"{t['id']:<4} {t['device_name']:<25} {t['created_at'][:19]:<25} {last_used[:19]:<25} {status}")
    elif cmd in ("revoke", "rm", "delete"):
        if len(sys.argv) < 3:
            print("Usage: agy-token.py revoke <token_id>")
            sys.exit(1)
        token_id = int(sys.argv[2])
        if revoke_device_token(token_id):
            print(f"Token {token_id} revoked successfully.")
        else:
            print(f"Token {token_id} not found or already revoked.")
    else:
        print(f"Unknown command: {cmd}")
        sys.exit(1)

if __name__ == "__main__":
    main()
