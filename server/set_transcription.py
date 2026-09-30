#!/usr/bin/env python3
import sys
import json
import urllib.request
import urllib.error

def main():
    if len(sys.argv) < 3:
        print("Usage: python3 set_transcription.py <path_or_attachment_id> <transcription>")
        sys.exit(1)

    target = sys.argv[1].strip()
    transcription = sys.argv[2].strip()

    url = "http://127.0.0.1:8765/v1/attachments/transcription"
    payload = json.dumps({
        "path": target,
        "attachment_id": target if target.startswith("att_") else None,
        "transcription": transcription,
    }).encode("utf-8")

    req = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/json"}
    )

    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            print(f"OK: Transcription updated for {target}: '{transcription[:60]}...'")
    except urllib.error.HTTPError as e:
        err_msg = e.read().decode("utf-8", errors="replace")
        print(f"HTTPError {e.code}: {err_msg}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
