#!/usr/bin/env python3
"""Quizopia CLASS-M2 E2E smoke (issue #52) — throwaway dockerized stack.

Cross-account acceptance flow over the real HTTP API: teacher exam/session
authoring → additive class assignment (never clobbering other classes) →
teacher class-scoped session lists + assign picker → learner joined-class
detail + assigned sessions (no DRAFT leakage) → unassign/leave revocations
with attempt/result history preserved → authorization denials.

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
                  {"name": f"M2 Bank {run} {tag}", "code": f"QB-{run}-{tag}"}, token=token)
    check(f"[{tag}] bank create", s in (200, 201), f"{s}")
    md = ("Câu 1 [SINGLE_CHOICE]: 2 + 2 bằng bao nhiêu?\n"
          "*A. 3\nB. 4\nC. 5\nD. 6\n")
    s, _ = req("POST", f"/api/question-banks/{bank['id']}/questions/import-markdown",
               {"markdown": md}, token=token)
    check(f"[{tag}] markdown import", s in (200, 201), f"{s}")
    s, exam = req("POST", "/api/exams", {"title": f"M2 Exam {run} {tag}", "code": f"EX-{run}-{tag}"}, token=token)
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



def create_session(token, exam_id, title, visibility="CLASS_RESTRICTED", schedule=False, future=False):
    if future:
        start_t = datetime.now(timezone.utc) + timedelta(minutes=30)
        end_t = datetime.now(timezone.utc) + timedelta(hours=3)
    else:
        start_t = datetime.now(timezone.utc) - timedelta(minutes=1)
        end_t = datetime.now(timezone.utc) + timedelta(hours=2)
    s, sess = req("POST", f"/api/exams/{exam_id}/sessions",
                  {"title": title, "visibility": visibility,
                   "startsAt": iso(start_t), "endsAt": iso(end_t),
                   "maxAttempts": 1, "durationMinutes": 15}, token=token)
    if s not in (200, 201):
        raise SystemExit(f"session create failed: {s} {sess}")
    if schedule:
        s2, _ = req("POST", f"/api/exam-sessions/{sess['id']}/schedule", token=token)
        check(f"schedule session {sess['id']}", s2 in (200, 201), f"{s2}")
    return sess["id"]

def main():
    run = uuid.uuid4().hex[:8]
    teacher_a = "m2a" + run
    teacher_b = "m2b" + run
    learner = "m2l" + run

    tok_a = register_and_verify(teacher_a)
    tok_b = register_and_verify(teacher_b)
    tok_l = register_and_verify(learner)
    req("POST", "/api/auth/upgrade-teacher", {}, tok_a)
    req("POST", "/api/auth/upgrade-teacher", {}, tok_b)

    exam_a = publish_exam(tok_a, run, "A")
    exam_b = publish_exam(tok_b, run, "B")

    # --- classes --------------------------------------------------------
    s, cls_a1 = req("POST", "/api/classrooms", {"code": "M2A1" + run, "name": "Math A1 " + run}, tok_a)
    s, cls_a2 = req("POST", "/api/classrooms", {"code": "M2A2" + run, "name": "Math A2 " + run}, tok_a)
    s, cls_b = req("POST", "/api/classrooms", {"code": "M2B" + run, "name": "Bio B " + run}, tok_b)
    check("teacher A creates two classes", s in (200, 201), str(s))
    a1, a2, cb = cls_a1["id"], cls_a2["id"], cls_b["id"]

    # Learner joins A1 (direct add) and CB (roster invite, verified email).
    code, found = req("GET", "/api/students/search?q=Display%20" + learner, None, tok_a)
    learner_id = found["items"][0]["userId"] if found.get("items") else None
    check("teacher A finds learner", learner_id is not None, str(found)[:150])
    s, r = req("POST", f"/api/classrooms/{a1}/members", {"userIds": [learner_id]}, tok_a)
    check("learner direct-added to A1", s == 200 and r.get("added") == 1, str(r)[:150])
    s, _ = req("POST", f"/api/classrooms/{cb}/roster",
               {"fullName": "Learner " + run, "email": learner + "@example.com"}, tok_b)
    check("learner roster-linked to CB", s in (200, 201), str(s))
    s, r = req("POST", f"/api/classrooms/{a2}/members", {"userIds": [learner_id]}, tok_a)
    check("learner member of A2 as well (shared-session case)", s == 200 and r.get("added") == 1, str(r)[:150])

    # --- sessions -------------------------------------------------------
    s1 = create_session(tok_a, exam_a, f"M2 Sitting {run}", schedule=True, future=True)
    s2_draft = create_session(tok_a, exam_a, f"M2 Draft {run}")            # stays DRAFT
    sb = create_session(tok_b, exam_b, f"M2 Bio {run}", schedule=True)     # B's own
    s3 = create_session(tok_a, exam_a, f"M2 Lifecycle {run}", schedule=True)
    s4 = create_session(tok_a, exam_a, f"M2 Open Sitting {run}", schedule=True)  # auto-OPENs in window

    # --- additive assignment (never clobbers other classes) -------------
    s, _ = req("POST", f"/api/classrooms/{a1}/sessions/{s1}", {}, tok_a)
    check("assign S1 to A1 (204)", s == 204, str(s))
    s, _ = req("POST", f"/api/classrooms/{a1}/sessions/{s1}", {}, tok_a)
    check("idempotent duplicate assign (204)", s == 204, str(s))
    s, _ = req("POST", f"/api/classrooms/{a2}/sessions/{s1}", {}, tok_a)
    check("assign S1 to A2 too (204)", s == 204, str(s))

    code, r = req("GET", f"/api/classrooms/{a1}/sessions", None, tok_a)
    ids = [i["sessionId"] for i in r.get("items", [])]
    check("teacher A1 list contains S1 exactly once", ids.count(s1) == 1, str(ids))
    code, r = req("GET", f"/api/classrooms/{a2}/sessions", None, tok_a)
    check("teacher A2 list still contains S1 (no clobber)", s1 in [i["sessionId"] for i in r.get("items", [])], str(r)[:150])

    # --- assign picker excludes duplicates/foreign/public ---------------
    code, r = req("GET", f"/api/classrooms/{a1}/sessions/assignable?size=50", None, tok_a)
    pids = [i["sessionId"] for i in r.get("items", [])]
    check("picker excludes already-assigned S1", s1 not in pids, str(pids))
    check("picker includes own DRAFT session", s2_draft in pids, str(pids))
    check("picker excludes other teacher's session", sb not in pids, str(pids))

    # --- authorization matrix -------------------------------------------
    s, _ = req("POST", f"/api/classrooms/{a1}/sessions/{sb}", {}, tok_a)
    check("assign foreign session denied (403)", s == 403, str(s))
    s, _ = req("POST", f"/api/classrooms/{cb}/sessions/{s1}", {}, tok_a)
    check("assign to foreign class denied (403)", s == 403, str(s))
    code, _ = req("GET", f"/api/classrooms/{a1}/sessions", None, tok_b)
    check("cross-teacher class list denied (403)", code == 403, str(code))
    code, _ = req("GET", f"/api/classrooms/{a1}/sessions", None, tok_l)
    check("learner cannot use teacher endpoint (403)", code == 403, str(code))
    code, _ = req("GET", f"/api/classrooms/joined/{a1}", None)
    check("unauthenticated joined detail (401)", code == 401, str(code))

    # --- learner class detail + sessions (no DRAFT leakage) -------------
    s, _ = req("POST", f"/api/classrooms/{a1}/sessions/{s2_draft}", {}, tok_a)
    check("DRAFT session assignable (204)", s == 204, str(s))
    code, detail = req("GET", f"/api/classrooms/joined/{a1}", None, tok_l)
    check("learner class detail", code == 200 and detail.get("classroomId") == a1
          and detail.get("teacherDisplayName") == "Display " + teacher_a, str(detail)[:200])
    code, r = req("GET", f"/api/classrooms/joined/{a1}/sessions", None, tok_l)
    ids = [i["sessionId"] for i in r.get("items", [])]
    check("learner sees SCHEDULED S1, not DRAFT s2", s1 in ids and s2_draft not in ids, str(ids))
    check("learner item has no participantCount/maxAttempts",
          all("participantCount" not in i and "maxAttempts" not in i for i in r.get("items", [])), str(r)[:200])

    # Shared session across the learner's two A-classes: once within EACH.
    code, r = req("GET", f"/api/classrooms/joined/{a2}/sessions", None, tok_l)
    ids2 = [i["sessionId"] for i in r.get("items", [])]
    check("learner sees shared S1 in A2 too (once)", ids2.count(s1) == 1, str(ids2))
    code, avail = req("GET", "/api/exam-sessions/available", None, tok_l)
    avail_ids = [a["sessionId"] for a in avail.get("items", [])]
    check("global discovery deduped (S1 once)", avail_ids.count(s1) == 1, str(avail_ids))

    # --- lifecycle: CLOSED remains visible as history -------------------
    s, _ = req("POST", f"/api/classrooms/{a1}/sessions/{s3}", {}, tok_a)
    check("assign S3 to A1", s == 204, str(s))
    s, _ = req("POST", f"/api/exam-sessions/{s3}/open", {}, tok_a)
    check("open S3", s in (200, 201), str(s))
    s, _ = req("POST", f"/api/exam-sessions/{s3}/close", {}, tok_a)
    check("close S3", s in (200, 201), str(s))
    code, r = req("GET", f"/api/classrooms/joined/{a1}/sessions", None, tok_l)
    closed = [i for i in r.get("items", []) if i["sessionId"] == s3]
    check("CLOSED S3 visible for history", closed and closed[0]["status"] == "CLOSED", str(r)[:200])
    code, r = req("GET", f"/api/classrooms/{a1}/sessions?status=CLOSED", None, tok_a)
    check("teacher status filter works", s3 in [i["sessionId"] for i in r.get("items", [])], str(r)[:150])

    # --- unassign (SCHEDULED-only mutation) keeps other classes ----------
    s, _ = req("DELETE", f"/api/classrooms/{a1}/sessions/{s1}", None, tok_a)
    check("unassign S1 from A1 (204)", s == 204, str(s))
    s, _ = req("DELETE", f"/api/classrooms/{a2}/sessions/{s1}", None, tok_a)
    check("unassign S1 from A2 (204)", s == 204, str(s))
    code, r = req("GET", f"/api/classrooms/joined/{a1}/sessions", None, tok_l)
    check("learner class list drops S1", s1 not in [i["sessionId"] for i in r.get("items", [])], str(r)[:150])
    code, avail = req("GET", "/api/exam-sessions/available", None, tok_l)
    check("global discovery drops S1 after full unassign",
          s1 not in [a["sessionId"] for a in avail.get("items", [])], str(avail)[:150])

    # --- OPEN session: attempt then LEAVE keeps history ------------------
    s, _ = req("POST", f"/api/classrooms/{a2}/sessions/{s4}", {}, tok_a)
    check("assign S4 to A2", s == 204, str(s))
    code, att = req("POST", f"/api/exam-sessions/{s4}/attempts", {"clientInstanceId": None}, tok_l)
    check("learner starts attempt on OPEN S4", code in (200, 201), f"{code} {att}")
    attempt_id = att.get("attemptId") if isinstance(att, dict) else None
    s, _ = req("POST", f"/api/classrooms/{a2}/leave", {}, tok_l)
    check("learner leaves A2 (attempt history case)", s == 204, str(s))
    code, _ = req("GET", f"/api/classrooms/joined/{a2}", None, tok_l)
    check("left class A2 detail is 404", code == 404, str(code))
    if attempt_id:
        code, _ = req("GET", f"/api/attempts/{attempt_id}", None, tok_l)
        check("attempt still readable after leaving", code == 200, str(code))

    # --- leaving revokes class-page access ------------------------------
    s, _ = req("POST", f"/api/classrooms/{cb}/leave", {}, tok_l)
    check("learner leaves CB", s == 204, str(s))
    code, _ = req("GET", f"/api/classrooms/joined/{cb}", None, tok_l)
    check("left class detail is 404", code == 404, str(code))
    code, _ = req("GET", f"/api/classrooms/joined/{cb}/sessions", None, tok_l)
    check("left class sessions is 404", code == 404, str(code))

    # --- replace-set endpoint still works (regression) ------------------
    s, _ = req("POST", f"/api/classrooms/{a1}/sessions/{s1}", {}, tok_a)
    check("re-assign S1 to A1 while SCHEDULED", s == 204, str(s))
    s, r = req("PUT", f"/api/exams/{exam_a}/sessions/{s1}/classes", [a1], tok_a)
    check("replace-set still works (OK)", s == 200, f"{s} {r}")

    print()
    print(f"CLASS-M2 E2E: {len(PASSED)} passed, {len(FAILED)} failed")
    if FAILED:
        for name, detail in FAILED:
            print(f"  FAILED: {name}: {detail}")
        sys.exit(1)


if __name__ == "__main__":
    main()
