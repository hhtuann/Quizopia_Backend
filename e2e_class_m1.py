#!/usr/bin/env python3
"""Quizopia CLASS-M1 E2E smoke (issue #50) — throwaway dockerized stack.

Cross-account acceptance flow over the real HTTP API:
two teachers -> learner joined via BOTH sources (direct add + verified-email
roster) -> GET /api/classrooms/joined lists both exactly once -> self-leave
(direct + roster paths, opt-out consent, rejoin by explicit accept) ->
teacher flows intact -> authorization denials.

Exit code 0 = all steps passed.
"""
import json
import sys
import time
import urllib.request
import urllib.error
import uuid

BASE = "http://localhost:8181"
MAILPIT = "http://localhost:8026"
PASSED = []
FAILED = []


def check(name, cond, detail=""):
    if cond:
        PASSED.append(name)
        print(f"  PASS {name}")
    else:
        FAILED.append((name, detail))
        print(f"  FAIL {name}: {detail}")


def req(method, path, body=None, token=None):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            payload = resp.read()
            ct = resp.headers.get("Content-Type", "")
            if "json" in ct:
                return resp.status, json.loads(payload)
            return resp.status, payload
    except urllib.error.HTTPError as e:
        payload = e.read()
        try:
            return e.code, json.loads(payload)
        except Exception:
            return e.code, payload


def mailpit_otp(to_email):
    for _ in range(30):
        try:
            with urllib.request.urlopen(MAILPIT + "/api/v1/messages?limit=10", timeout=5) as r:
                msgs = json.loads(r.read())["messages"]
            for m in reversed(msgs):
                if to_email.lower() in m["To"][0]["Address"].lower():
                    for tok in m["Snippet"].replace("<", " ").replace(">", " ").split():
                        if tok.isdigit() and len(tok) == 6:
                            return tok
        except Exception:
            pass
        time.sleep(1)
    return None


def register_and_verify(username):
    email = username + "@example.com"
    code, _ = req("POST", "/api/auth/register", {
        "username": username, "email": email, "password": "Passw0rd!",
        "displayName": "Display " + username, "phone": "0900000" + username[-4:]})
    if code not in (200, 201):
        raise SystemExit(f"register failed for {username}: {code}")
    otp = mailpit_otp(email)
    if not otp:
        raise SystemExit(f"no OTP mail for {email}")
    code, _ = req("POST", "/api/auth/verify-email", {"email": email, "otp": otp})
    if code not in (200, 204):
        raise SystemExit(f"verify failed for {email}: {code}")
    code, body = req("POST", "/api/auth/login", {"identifier": username, "password": "Passw0rd!"})
    if code != 200:
        raise SystemExit(f"login failed for {username}: {code}")
    return body.get("accessToken") or body.get("access_token")


def main():
    run = uuid.uuid4().hex[:8]
    teacher_a = "e2a" + run
    teacher_b = "e2b" + run
    learner = "e2l" + run
    stranger = "e2s" + run

    token_a = register_and_verify(teacher_a)
    token_b = register_and_verify(teacher_b)
    token_l = register_and_verify(learner)
    token_s = register_and_verify(stranger)
    req("POST", "/api/auth/upgrade-teacher", {}, token_a)
    req("POST", "/api/auth/upgrade-teacher", {}, token_b)

    # --- classes -------------------------------------------------------
    code, body = req("POST", "/api/classrooms",
                     {"code": "MA" + run, "name": "Math A " + run}, token_a)
    check("teacher A creates class", code == 201, str(code))
    class_a = body["id"]

    code, body = req("POST", "/api/classrooms",
                     {"code": "PB" + run, "name": "Physics B " + run}, token_b)
    check("teacher B creates class", code == 201, str(code))
    class_b = body["id"]

    # --- source 1: teacher A direct add --------------------------------
    code, body = req("GET", "/api/students/search?q=Display%20" + learner, None, token_a)
    learner_id = body["items"][0]["userId"] if code == 200 and body.get("items") else None
    check("teacher A finds learner by search", learner_id is not None, str(body)[:200])
    code, body = req("POST", f"/api/classrooms/{class_a}/members",
                     {"userIds": [learner_id]}, token_a)
    check("direct add succeeds", code == 200 and body.get("added") == 1, str(body)[:200])

    # --- source 2: teacher B roster invite (verified email auto-links) --
    code, body = req("POST", f"/api/classrooms/{class_b}/roster",
                     {"fullName": "Learner " + run,
                      "email": learner + "@example.com"}, token_b)
    check("roster invite auto-links (verified email)",
          code == 201 and body.get("status") == "LINKED", str(body)[:200])

    # --- joined projection: BOTH sources, exactly once -----------------
    code, body = req("GET", "/api/classrooms/joined", None, token_l)
    items = body.get("items", [])
    ids = [i["classroomId"] for i in items]
    check("joined lists both classes", code == 200 and sorted(ids) == sorted([class_a, class_b]),
          str(body)[:300])
    card_a = next((i for i in items if i["classroomId"] == class_a), {})
    card_b = next((i for i in items if i["classroomId"] == class_b), {})
    check("joined card shows class NAME", card_a.get("name") == "Math A " + run, str(card_a)[:200])
    check("joined card shows teacher display name",
          card_a.get("teacherDisplayName") == "Display " + teacher_a
          and card_b.get("teacherDisplayName") == "Display " + teacher_b, str(card_b)[:200])
    check("joined card has status + joinedAt",
          card_a.get("status") == "ACTIVE" and card_a.get("joinedAt"), str(card_a)[:200])
    check("no duplicate classroom cards", len(ids) == len(set(ids)), str(ids))

    # --- unauthorized / cross-account ----------------------------------
    code, _ = req("GET", "/api/classrooms/joined")
    check("joined requires auth (401)", code == 401, str(code))
    code, body = req("GET", "/api/classrooms/joined", None, token_s)
    check("stranger sees zero classes", code == 200 and body.get("totalElements") == 0, str(body)[:200])

    # --- leave: direct-add path ----------------------------------------
    code, _ = req("POST", f"/api/classrooms/{class_a}/leave", {}, token_l)
    check("learner leaves direct-add class (204)", code == 204, str(code))
    code, body = req("GET", "/api/classrooms/joined", None, token_l)
    ids = [i["classroomId"] for i in body.get("items", [])]
    check("class A card gone; class B remains", ids == [class_b], str(ids))
    code, body = req("POST", f"/api/classrooms/{class_a}/leave", {}, token_l)
    check("repeat leave is defined 404", code == 404 and body.get("code") == "CLASSROOM_NOT_FOUND",
          str(code))
    code, _ = req("POST", f"/api/classrooms/{class_b}/leave", {}, token_s)
    check("stranger cannot leave a class they are not in (404)", code == 404, str(code))

    # --- consent: re-invite stays PENDING; explicit accept rejoins ------
    code, body = req("POST", f"/api/classrooms/{class_a}/roster",
                     {"fullName": "Learner " + run,
                      "email": learner + "@example.com"}, token_a)
    check("re-invite after opt-out stays PENDING (no auto-relink)",
          code == 201 and body.get("status") == "PENDING", str(body)[:200])
    entry_id = body.get("id")
    code, body = req("GET", "/api/classrooms/roster/invitations", None, token_l)
    invite = next((i for i in body if i.get("id") == entry_id), {})
    check("invitation visible with class name + teacher",
          invite.get("classroomName") == "Math A " + run
          and invite.get("teacherDisplayName") == "Display " + teacher_a, str(invite)[:250])
    code, body = req("POST", f"/api/classrooms/roster/{entry_id}/accept", {}, token_l)
    check("explicit accept rejoins", code == 200 and body.get("status") == "LINKED", str(body)[:200])
    code, body = req("GET", "/api/classrooms/joined", None, token_l)
    ids = [i["classroomId"] for i in body.get("items", [])]
    check("joined back to both classes exactly once",
          sorted(ids) == sorted([class_a, class_b]), str(ids))

    # --- leave: roster-linked path -------------------------------------
    code, _ = req("POST", f"/api/classrooms/{class_b}/leave", {}, token_l)
    check("learner leaves roster-linked class (204)", code == 204, str(code))
    code, body = req("GET", f"/api/classrooms/{class_b}/roster?page=0&size=50", None, token_b)
    entries = body.get("items", [])
    declined = [e for e in entries if e.get("status") == "DECLINED"]
    check("roster entry is DECLINED (no stale LINKED)", len(declined) == 1, str(entries)[:300])
    code, body = req("GET", "/api/classrooms/joined", None, token_l)
    ids = [i["classroomId"] for i in body.get("items", [])]
    check("only class A remains", ids == [class_a], str(ids))

    # --- teacher flows intact ------------------------------------------
    code, body = req("GET", "/api/classrooms/my", None, token_a)
    check("teacher /my still lists owned classes",
          code == 200 and class_a in [c["id"] for c in body.get("items", [])], str(body)[:200])
    code, body = req("POST", f"/api/classrooms/{class_a}/members",
                     {"userIds": [learner_id]}, token_a)
    check("duplicate direct add reported (partial success)",
          code == 200 and body.get("added") == 0 and learner_id in (body.get("duplicated") or []),
          str(body)[:200])

    # --- Final-M1 gate F1: opt-out blocks DIRECT re-add too --------------
    code, _ = req("POST", f"/api/classrooms/{class_a}/leave", {}, token_l)
    check("learner leaves class A again", code == 204, str(code))
    code, body = req("POST", f"/api/classrooms/{class_a}/members",
                     {"userIds": [learner_id]}, token_a)
    check("direct re-add after opt-out is REFUSED with per-user result",
          code == 200 and body.get("added") == 0 and learner_id in (body.get("declined") or []),
          str(body)[:200])
    code, body = req("GET", "/api/classrooms/joined", None, token_l)
    check("learner still out after refused re-add",
          class_a not in [i["classroomId"] for i in body.get("items", [])], str(body)[:200])
    code, body = req("POST", f"/api/classrooms/{class_a}/roster",
                     {"fullName": "Learner " + run,
                      "email": learner + "@example.com"}, token_a)
    check("invitation is the rejoin path (stays PENDING)",
          code == 201 and body.get("status") == "PENDING", str(body)[:200])
    rejoin_entry = body.get("id")
    code, body = req("POST", f"/api/classrooms/roster/{rejoin_entry}/accept", {}, token_l)
    check("learner accepts and is back in class A",
          code == 200 and body.get("status") == "LINKED", str(body)[:200])

    # --- teacher remove drops the joined card (existing flow intact) ----
    code, _ = req("DELETE", f"/api/classrooms/{class_a}/members/{learner_id}", None, token_a)
    check("teacher remove member succeeds", code in (200, 204), str(code))
    code, body = req("GET", "/api/classrooms/joined", None, token_l)
    ids = [i["classroomId"] for i in body.get("items", [])]
    check("removed learner no longer sees the class", class_a not in ids, str(ids))

    print()
    print(f"CLASS-M1 E2E: {len(PASSED)} passed, {len(FAILED)} failed")
    if FAILED:
        for name, detail in FAILED:
            print(f"  FAILED: {name}: {detail}")
        sys.exit(1)


if __name__ == "__main__":
    main()
