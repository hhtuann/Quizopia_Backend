#!/usr/bin/env python3
"""Quizopia CLASS-M3 E2E smoke (issue #54) — throwaway dockerized stack.

Cross-account acceptance flow over the real HTTP API covering the issue #54
scenario matrix: historical membership attribution (leave/remove/rejoin,
join-after-close), class-scoped gradebook + analytics with BEST-result
semantics, learner my-results isolation, XLSX export, authorization denials,
and M1/M2 regression checks.

Exit code 0 = all steps passed.
"""
import json
import sys
import time
import urllib.request
import urllib.error
import uuid
from datetime import datetime, timedelta, timezone

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


def iso(dt):
    return dt.isoformat().replace("+00:00", "Z")


def publish_exam(token, run, tag):
    """Bank → markdown import → exam → composition → publish. Returns exam id."""
    s, bank = req("POST", "/api/question-banks",
                  {"name": f"M3 Bank {run} {tag}", "code": f"QB3-{run}-{tag}"}, token=token)
    check(f"[{tag}] bank create", s in (200, 201), f"{s}")
    md = ("Câu 1 [SINGLE_CHOICE]: 2 + 2 bằng bao nhiêu?\n"
          "*A. 3\nB. 4\nC. 5\nD. 6\n")
    s, _ = req("POST", f"/api/question-banks/{bank['id']}/questions/import-markdown",
               {"markdown": md}, token=token)
    check(f"[{tag}] markdown import", s in (200, 201), f"{s}")
    s, exam = req("POST", "/api/exams", {"title": f"M3 Exam {run} {tag}", "code": f"E3-{run}-{tag}"},
                  token=token)
    check(f"[{tag}] exam create", s in (200, 201), f"{s}")
    s, qs = req("GET", f"/api/question-banks/{bank['id']}/questions", token=token)
    qids = [q["id"] for q in qs.get("items", [])]
    s, _ = req("PUT", f"/api/exams/{exam['id']}/composition",
               {"expectedExamVersion": 0, "durationMinutes": 20, "instructions": "good luck",
                "sections": [{"title": "S1", "position": 0, "instructions": None,
                              "questions": [{"sourceQuestionId": q, "position": i, "defaultPoints": 10}
                                            for i, q in enumerate(qids)]}]}, token=token)
    check(f"[{tag}] composition", s == 200, f"{s}")
    s, pub = req("POST", f"/api/exams/{exam['id']}/publish", {"expectedExamVersion": 1}, token=token)
    check(f"[{tag}] publish", s == 200 and pub.get("status") == "PUBLISHED", f"{s}")
    return exam["id"]


def create_session(token, exam_id, title, max_attempts=1, future=False):
    if future:
        start_t = datetime.now(timezone.utc) + timedelta(minutes=30)
        end_t = datetime.now(timezone.utc) + timedelta(hours=3)
    else:
        start_t = datetime.now(timezone.utc) - timedelta(minutes=1)
        end_t = datetime.now(timezone.utc) + timedelta(hours=2)
    s, sess = req("POST", f"/api/exams/{exam_id}/sessions",
                  {"title": title, "visibility": "CLASS_RESTRICTED",
                   "startsAt": iso(start_t), "endsAt": iso(end_t),
                   "maxAttempts": max_attempts, "durationMinutes": 15}, token=token)
    if s not in (200, 201):
        raise SystemExit(f"session create failed: {s} {sess}")
    return sess["id"]


def answer_and_submit(token, attempt_id, run, correct):
    """Answers the single SINGLE_CHOICE question then submits. correct=True → option A."""
    s, det = req("GET", f"/api/attempts/{attempt_id}", None, token)
    qviews = det.get("questions", []) if isinstance(det, dict) else []
    seq = 0
    for qv in qviews:
        opts = qv.get("options") or []
        if qv.get("questionType") == "SINGLE_CHOICE" and opts:
            key = opts[0]["optionKey"] if correct else opts[1]["optionKey"]
            payload = {"selectedOptionKey": key}
        else:
            payload = {"value": "2.50"}
        seq += 1
        s, _ = req("PUT", f"/api/attempts/{attempt_id}/answers",
                   {"attemptQuestionId": qv["attemptQuestionId"],
                    "answerPayload": payload, "sequenceNumber": seq}, token=token)
    s, _ = req("POST", f"/api/attempts/{attempt_id}/submit",
               {"submissionIdempotencyKey": f"m3-{run}-{attempt_id}"}, token=token)
    return s in (200, 201)


def gradebook(token, class_id, **params):
    qs = "&".join(f"{k}={v}" for k, v in params.items() if v is not None)
    path = f"/api/classrooms/{class_id}/gradebook" + ("?" + qs if qs else "")
    return req("GET", path, None, token)


def row_for(students, user_id):
    for row in students:
        if row["userId"] == user_id:
            return row
    return None


def main():
    run = uuid.uuid4().hex[:8]
    ta = "m3a" + run
    tb = "m3b" + run
    l1u = "m3l1" + run   # full-flow learner (multi-class, multi-attempt, leave)
    l2u = "m3l2" + run   # removed-then-rejoined learner
    l3u = "m3l3" + run   # class-B learner (no attempt) + late joiner

    tok_a = register_and_verify(ta)
    tok_b = register_and_verify(tb)
    tok_1 = register_and_verify(l1u)
    tok_2 = register_and_verify(l2u)
    tok_3 = register_and_verify(l3u)
    req("POST", "/api/auth/upgrade-teacher", {}, tok_a)
    req("POST", "/api/auth/upgrade-teacher", {}, tok_b)

    exam_a = publish_exam(tok_a, run, "A")
    exam_b = publish_exam(tok_b, run, "B")

    # --- classes (scenarios 1-3) ----------------------------------------
    s, cls = req("POST", "/api/classrooms", {"code": "M3A1" + run, "name": "Math A1 " + run}, tok_a)
    a1 = cls["id"]
    s, cls = req("POST", "/api/classrooms", {"code": "M3A2" + run, "name": "Math A2 " + run}, tok_a)
    a2 = cls["id"]
    s, cls = req("POST", "/api/classrooms", {"code": "M3B" + run, "name": "Bio B " + run}, tok_b)
    cb = cls["id"]
    check("teacher A creates two classes (A1, A2)", a1 and a2, f"{a1} {a2}")
    check("teacher B creates an unrelated classroom", cb is not None, str(cb))

    def find_user(tok, username):
        code, found = req("GET", "/api/students/search?q=Display%20" + username, None, tok)
        items = found.get("items", []) if isinstance(found, dict) else []
        return items[0]["userId"] if items else None

    l1 = find_user(tok_a, l1u)
    l2 = find_user(tok_a, l2u)
    l3 = find_user(tok_b, l3u)
    check("learners found", None not in (l1, l2, l3), f"{l1} {l2} {l3}")

    # Scenario 4: student joins multiple classes (direct adds).
    s, r = req("POST", f"/api/classrooms/{a1}/members", {"userIds": [l1, l2]}, tok_a)
    check("L1+L2 join A1 (direct add)", s == 200 and r.get("added") == 2, str(r)[:150])
    s, r = req("POST", f"/api/classrooms/{a2}/members", {"userIds": [l1]}, tok_a)
    check("L1 joins A2 too (multi-class)", s == 200 and r.get("added") == 1, str(r)[:150])
    s, r = req("POST", f"/api/classrooms/{cb}/members", {"userIds": [l3]}, tok_b)
    check("L3 joins B", s == 200 and r.get("added") == 1, str(r)[:150])

    # Scenario 5: one session assigned to BOTH A1 and A2 (multi-attempt →
    # scenario 12 needs maxAttempts=2).
    s1 = create_session(tok_a, exam_a, f"M3 Shared {run}", max_attempts=2)
    s, _ = req("POST", f"/api/classrooms/{a1}/sessions/{s1}", {}, tok_a)
    check("assign S1 to A1", s == 204, str(s))
    s, _ = req("POST", f"/api/classrooms/{a2}/sessions/{s1}", {}, tok_a)
    check("assign S1 to A2 (shared session)", s == 204, str(s))
    s, _ = req("POST", f"/api/exam-sessions/{s1}/schedule", {}, tok_a)
    check("schedule S1", s in (200, 201), str(s))

    # Scenario 12 + 6: two attempts — first WRONG, second RIGHT → BEST = #2.
    code, att1 = req("POST", f"/api/exam-sessions/{s1}/attempts", {"clientInstanceId": None}, tok_1)
    check("L1 starts attempt 1", code in (200, 201), f"{code}")
    ok1 = answer_and_submit(tok_1, att1["attemptId"], run, correct=False)
    check("L1 submits attempt 1 (wrong)", ok1, "submit failed")
    code, res1 = req("GET", f"/api/attempts/{att1['attemptId']}/result", None, tok_1)
    check("L1 attempt 1 graded 0%", code == 200 and res1.get("percentage") == 0, f"{code} {res1}")
    code, att2 = req("POST", f"/api/exam-sessions/{s1}/attempts", {"clientInstanceId": None}, tok_1)
    check("L1 starts attempt 2", code in (200, 201), f"{code}")
    ok2 = answer_and_submit(tok_1, att2["attemptId"], run, correct=True)
    check("L1 submits attempt 2 (right)", ok2, "submit failed")
    code, res2 = req("GET", f"/api/attempts/{att2['attemptId']}/result", None, tok_1)
    check("L1 attempt 2 graded 100%", code == 200 and res2.get("percentage") == 100, f"{code} {res2}")

    # Scenario 7/12: gradebook in BOTH classes shows BEST (attempt 2), once.
    code, g1 = gradebook(tok_a, a1)
    check("A1 gradebook readable", code == 200, str(code))
    row1 = row_for(g1.get("students", []), l1)
    check("A1: L1 cell SCORED with BEST attempt", row1 and
          row1["cells"][0]["status"] == "SCORED" and
          row1["cells"][0]["bestAttemptId"] == att2["attemptId"] and
          row1["cells"][0]["percentage"] == 100 and
          row1["cells"][0]["attemptCount"] == 2, str(row1)[:200])
    code, g2 = gradebook(tok_a, a2)
    row1b = row_for(g2.get("students", []), l1)
    check("A2: same BEST result, one attempt object (no duplication)", row1b and
          row1b["cells"][0]["bestAttemptId"] == att2["attemptId"] and
          len(row1b["cells"]) == 1, str(row1b)[:200])
    # L2 (member, no attempt) shows NOT_ATTEMPTED — never a zero score.
    row2 = row_for(g1.get("students", []), l2)
    check("A1: L2 NOT_ATTEMPTED with null score", row2 and
          row2["cells"][0]["status"] == "NOT_ATTEMPTED" and
          row2["cells"][0]["score"] is None and
          row2["meanPercentage"] is None, str(row2)[:200])

    # Scenario 8: per-class completion differs (A1: 1/2 = 50%, A2: 1/1 = 100%).
    col1 = g1["sessions"][0]
    col2 = g2["sessions"][0]
    check("A1 completion 50% (cohort 2, submitted 1)",
          col1["cohortSize"] == 2 and col1["submittedCount"] == 1
          and col1["completionPercentage"] == 50, str(col1)[:200])
    check("A2 completion 100% (cohort 1, submitted 1)",
          col2["cohortSize"] == 1 and col2["submittedCount"] == 1
          and col2["completionPercentage"] == 100, str(col2)[:200])
    check("A1 average% = 100 (BEST of graded only)", col1["averagePercentage"] == 100,
          str(col1.get("averagePercentage")))

    # Analytics (single session + overview) consistent with the matrix.
    code, det = req("GET", f"/api/classrooms/{a1}/analytics?sessionId={s1}", None, tok_a)
    check("A1 analytics detail matches gradebook", code == 200 and
          det["cohortSize"] == 2 and det["submittedCount"] == 1 and det["gradedCount"] == 1
          and det["notAttemptedCount"] == 1 and det["averagePercentage"] == 100
          and det["maximumPercentage"] == 100, str(det)[:250])
    code, over = req("GET", f"/api/classrooms/{a1}/analytics", None, tok_a)
    check("A1 analytics overview KPIs", code == 200 and
          over["currentRosterSize"] == 2 and over["assignedSessionCount"] == 1
          and over["overallCompletionPercentage"] == 50, str(over)[:250])

    # Scenario 16: XLSX export (owner-only, matches server data presence).
    code, xlsx = req("GET", f"/api/classrooms/{a1}/gradebook/export", None, tok_a)
    check("export returns XLSX", code == 200 and isinstance(xlsx, bytes) and
          xlsx[:2] == b"PK" and len(xlsx) > 1000, f"{code} {type(xlsx)}")
    code, _ = req("GET", f"/api/classrooms/{a1}/gradebook/export", None, tok_b)
    check("export denied to other teacher", code == 403, str(code))
    code, _ = req("GET", f"/api/classrooms/{a1}/gradebook/export", None, tok_1)
    check("export denied to learner", code == 403, str(code))

    # Scenario 14/15: authorization matrix.
    for path in [f"/api/classrooms/{a1}/gradebook", f"/api/classrooms/{a1}/analytics",
                 f"/api/classrooms/{a1}/gradebook/sessions"]:
        code, _ = req("GET", path, None, tok_b)
        check(f"teacher B denied {path.split('/')[-1]}", code == 403, str(code))
        code, _ = req("GET", path, None, tok_1)
        check(f"learner denied {path.split('/')[-1]}", code == 403, str(code))
    code, _ = req("GET", "/api/classrooms/999999999/gradebook", None, tok_a)
    check("unknown classroom gradebook 404", code == 404, str(code))

    # Scenario 15: my-results is self-scoped.
    code, mine = req("GET", f"/api/classrooms/joined/{a1}/my-results", None, tok_1)
    mine_items = mine.get("items", []) if isinstance(mine, dict) else []
    s1_item = next((i for i in mine_items if i["sessionId"] == s1), None)
    check("L1 my-results shows own SCORED outcome", code == 200 and s1_item and
          s1_item["resultStatus"] == "SCORED" and s1_item["percentage"] == 100 and
          s1_item["bestAttemptId"] == att2["attemptId"], str(mine)[:250])
    code, mine2 = req("GET", f"/api/classrooms/joined/{a1}/my-results", None, tok_2)
    s1_item2 = next((i for i in (mine2.get("items", []) if isinstance(mine2, dict) else [])
                     if i["sessionId"] == s1), None)
    check("L2 my-results shows own NOT_ATTEMPTED (no scores leaked)", code == 200 and
          s1_item2 and s1_item2["resultStatus"] == "NOT_ATTEMPTED" and
          s1_item2["percentage"] is None, str(mine2)[:250])

    # Scenario 9: L1 leaves A1 — history retained, class access revoked.
    s, _ = req("POST", f"/api/classrooms/{a1}/leave", {}, tok_1)
    check("L1 leaves A1", s == 204, str(s))
    code, _ = req("GET", f"/api/classrooms/joined/{a1}/my-results", None, tok_1)
    check("left class my-results is 404", code == 404, str(code))
    code, hist = req("GET", "/api/attempts/my?size=50", None, tok_1)
    hist_items = hist.get("items", []) if isinstance(hist, dict) else []
    check("personal attempt history preserved after leave", code == 200 and
          len(hist_items) >= 2, f"{code} {len(hist_items)}")
    code, g1b = gradebook(tok_a, a1)
    row1_after = row_for(g1b.get("students", []), l1)
    check("A1 gradebook retains L1's history (left marker)", row1_after and
          row1_after["currentMember"] is False and
          row1_after["cells"][0]["status"] == "SCORED", str(row1_after)[:200])
    col_after = g1b["sessions"][0]
    check("cohort unchanged after leave (attribution kept)", col_after["cohortSize"] == 2 and
          col_after["submittedCount"] == 1, str(col_after)[:200])

    # Scenario 10: teacher removes L2 — attribution kept, roster shrinks.
    s, _ = req("DELETE", f"/api/classrooms/{a1}/members/{l2}", None, tok_a)
    check("teacher removes L2", s in (200, 204), str(s))
    code, g1c = gradebook(tok_a, a1)
    row2_after = row_for(g1c.get("students", []), l2)
    check("removed L2 still in cohort (NOT_ATTEMPTED, left)", row2_after and
          row2_after["currentMember"] is False and
          row2_after["cells"][0]["status"] == "NOT_ATTEMPTED", str(row2_after)[:200])

    # Scenario 11: L2 rejoins via direct re-add (no opt-out on teacher removal).
    s, r = req("POST", f"/api/classrooms/{a1}/members", {"userIds": [l2]}, tok_a)
    check("L2 rejoins A1 (direct re-add allowed after removal)", s == 200 and
          r.get("added") == 1, str(r)[:150])
    code, g1d = gradebook(tok_a, a1)
    row2_re = row_for(g1d.get("students", []), l2)
    check("rejoined L2 currentMember again", row2_re and row2_re["currentMember"] is True,
          str(row2_re)[:120])

    # Scenario 13: joining AFTER closure must not fabricate non-submitters.
    s_late = create_session(tok_b, exam_b, f"M3 Late {run}", future=True)
    s, _ = req("POST", f"/api/classrooms/{cb}/sessions/{s_late}", {}, tok_b)
    check("assign late session to B", s == 204, str(s))
    s, _ = req("POST", f"/api/exam-sessions/{s_late}/schedule", {}, tok_b)
    check("schedule late session", s in (200, 201), str(s))
    # Early-close it now (open then close immediately).
    s, _ = req("POST", f"/api/exam-sessions/{s_late}/open", {}, tok_b)
    check("open late session", s in (200, 201), str(s))
    s, _ = req("POST", f"/api/exam-sessions/{s_late}/close", {}, tok_b)
    check("close late session early", s in (200, 201), str(s))
    # A NEW learner joins B only AFTER the window closed.
    l4u = "m3l4" + run
    tok_4 = register_and_verify(l4u)
    l4 = find_user(tok_b, l4u)
    s, r = req("POST", f"/api/classrooms/{cb}/members", {"userIds": [l4]}, tok_b)
    check("L4 joins B after closure", s == 200 and r.get("added") == 1, str(r)[:150])
    code, gb_b = gradebook(tok_b, cb)
    col_late = next((c for c in gb_b.get("sessions", []) if c["id"] == s_late), None)
    check("late joiner NOT in closed session cohort (no fabricated non-submitter)",
          col_late and col_late["cohortSize"] == 0 and col_late["completionPercentage"] is None,
          str(col_late)[:200])
    row_l3 = row_for(gb_b.get("students", []), l3)
    check("L3 (pre-closure member) in cohort as NOT_ATTEMPTED", row_l3 and
          any(c["status"] == "NOT_ATTEMPTED" for c in row_l3["cells"]), str(row_l3)[:200])

    # Scenario 17: session-wide results/statistics still work (creator-only).
    code, results = req("GET", f"/api/exam-sessions/{s1}/results", None, tok_a)
    check("session-wide results still readable by creator", code == 200 and
          results.get("totalElements", 0) >= 1, f"{code}")
    code, stats = req("GET", f"/api/exam-sessions/{s1}/statistics", None, tok_a)
    check("session statistics still work", code == 200 and
          stats.get("submittedStudentCount", stats.get("submitted", 0)) >= 1, str(stats)[:150])
    code, _ = req("GET", f"/api/exam-sessions/{s1}/statistics", None, tok_b)
    check("session statistics denied to other teacher", code == 403, str(code))

    # Scenario 18: M1/M2 regressions.
    code, joined = req("GET", "/api/classrooms/joined", None, tok_2)
    joined_items = joined.get("items", []) if isinstance(joined, dict) else []
    check("M1: joined list still works", code == 200 and
          any(c["id"] == a1 for c in joined_items), f"{code}")
    code, picker = req("GET", f"/api/classrooms/{a1}/sessions/assignable?size=50", None, tok_a)
    picker_items = picker.get("items", []) if isinstance(picker, dict) else []
    check("M2: assignable picker excludes assigned S1", code == 200 and
          all(i["sessionId"] != s1 for i in picker_items), f"{code}")
    code, lsess = req("GET", f"/api/classrooms/joined/{a1}/sessions", None, tok_2)
    check("M2: learner assigned-session list still works", code == 200, str(code))

    print()
    print(f"CLASS-M3 E2E: {len(PASSED)} passed, {len(FAILED)} failed")
    if FAILED:
        for name, detail in FAILED:
            print(f"  FAILED: {name}: {detail}")
        sys.exit(1)


if __name__ == "__main__":
    main()
