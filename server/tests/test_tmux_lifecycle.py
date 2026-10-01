import os
import json
import urllib.request
import urllib.error

BASE_URL = "http://127.0.0.1:8765"
TOKEN = ""
with open("/root/agy-gateway/.env", "r") as f:
    for line in f:
        if line.startswith("AUTH_TOKEN="):
            TOKEN = line.strip().split("=", 1)[1]
            break

HEADERS = {
    "Authorization": f"Bearer {TOKEN}",
    "Content-Type": "application/json"
}

def make_req(method: str, path: str, data: dict = None):
    url = f"{BASE_URL}{path}"
    body = json.dumps(data).encode("utf-8") if data else None
    req = urllib.request.Request(url, data=body, headers=HEADERS, method=method)
    try:
        with urllib.request.urlopen(req) as resp:
            content = resp.read().decode("utf-8")
            return resp.status, json.loads(content) if content else {}
    except urllib.error.HTTPError as e:
        content = e.read().decode("utf-8")
        return e.code, json.loads(content) if content else {}

def test_tmux_lifecycle():
    # 1. Check list chats returns is_active field
    status, chats = make_req("GET", "/v1/chats")
    assert status == 200, f"GET /v1/chats returned {status}: {chats}"
    assert isinstance(chats, list)
    if chats:
        assert "is_active" in chats[0], "is_active missing from ChatSummary"

    # Use a dummy test conversation ID to test rename, stop, delete safely
    test_id = "test-chat-lifecycle-dummy-id"
    
    # Insert dummy entry into conversation_summaries.db so rename and delete work
    import sqlite3
    conn = sqlite3.connect("/root/.gemini/antigravity-cli/conversation_summaries.db")
    cur = conn.cursor()
    cur.execute(
        "INSERT OR REPLACE INTO conversation_summaries (conversation_id, title, preview, step_count, last_modified_time, workspace_uris, status, last_user_input_time) VALUES (?, ?, ?, ?, datetime('now'), ?, ?, datetime('now'))",
        (test_id, "Old Test Title", "preview", 0, '["file:///root"]', "active")
    )
    conn.commit()
    conn.close()

    # 2. Test PATCH rename
    status, patch_res = make_req("PATCH", f"/v1/chats/{test_id}", {"title": "New Test Title"})
    assert status == 200, f"PATCH failed: {status} {patch_res}"
    assert patch_res.get("title") == "New Test Title"

    # 3. Test POST stop
    status, stop_res = make_req("POST", f"/v1/chats/{test_id}/stop")
    assert status == 200, f"POST stop failed: {status} {stop_res}"

    # 4. Test DELETE
    status, del_res = make_req("DELETE", f"/v1/chats/{test_id}")
    assert status == 200, f"DELETE failed: {status} {del_res}"
    assert del_res.get("status") == "deleted"

    print("Tmux lifecycle test passed successfully!")

if __name__ == "__main__":
    test_tmux_lifecycle()
