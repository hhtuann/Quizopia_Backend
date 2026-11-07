#!/usr/bin/env python3
"""Quizopia user-centric E2E smoke (throwaway dockerized stack).

Covers the mandatory acceptance flow end-to-end over the real HTTP API:
registration(+OTP via Mailpit) -> teacher upgrade -> bank create -> markdown
import -> exam create/publish -> classroom -> student search/add member ->
CLASS_RESTRICTED session -> join/start/autosave/submit -> results/stats ->
PUBLIC session for outsider -> session sharing eligibility -> unauthorized
access denials -> decommissioned endpoints -> identity M5.

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
    email = f"{username}@e2e.local"
    s, r = req("POST", "/api/auth/register", {
        "username": username, "email": email, "displayName": username.title(),
        "password": "E2ePass!123", "phone": "+84900000001"})
    check(f"register {username}", s in (200, 201), f"{s} {r}")
    otp = mailpit_otp(email)
    check(f"otp delivered {username}", otp is not None, "no OTP in mailpit")
    if otp:
        s, r = req("POST", "/api/auth/verify-email", {"email": email, "otp": otp})
        check(f"verify-email {username}", s in (200, 201), f"{s} {r}")
    s, r = req("POST", "/api/auth/login", {"identifier": username, "password": "E2ePass!123"})
    check(f"login {username}", s == 200, f"{s} {r}")
    return r.get("accessToken") if isinstance(r, dict) else None


def iso(dt):
    return dt.isoformat().replace("+00:00", "Z")


def main():
    run = uuid.uuid4().hex[:6]
    teacher = f"e2eteacher{run}"
    student = f"e2estudent{run}"
    outsider = f"e2eout{run}"

    print("== identity ==")
    t_tok = register_and_verify(teacher)
    s_tok = register_and_verify(student)
    o_tok = register_and_verify(outsider)
    check("all tokens", all([t_tok, s_tok, o_tok]))

    s, me = req("GET", "/api/auth/me", token=t_tok)
    check("me roles student-only", s == 200 and me.get("roles") == ["STUDENT"], f"{me.get('roles')}")

    s, r = req("POST", "/api/auth/upgrade-teacher", token=t_tok)
    check("teacher self-upgrade", s in (200, 201), f"{s} {r}")
    s, me = req("GET", "/api/auth/me", token=t_tok)
    check("dual role now", s == 200 and set(me.get("roles", [])) == {"STUDENT", "TEACHER"}, f"{me.get('roles')}")

    s, r = req("GET", "/api/account/security", token=s_tok)
    check("M5 security endpoint", s == 200, f"{s}")
    s, r = req("PUT", "/api/account/profile", {"displayName": "Renamed Student"}, token=s_tok)
    check("M5 profile update", s == 200, f"{s} {r}")

    print("== catalog ==")
    s, grades = req("GET", "/api/grade-levels", token=t_tok)
    check("grade-levels global catalog", s == 200 and len(grades.get("items", [])) >= 3, f"{s}")
    s, subjects = req("GET", "/api/subjects", token=t_tok)
    check("subjects global catalog", s == 200 and len(subjects.get("items", [])) >= 15, f"{s}")
    s, r = req("GET", "/api/subjects", token=s_tok)
    check("subjects denied to STUDENT (fail-closed)", s in (403, 404), f"{s}")

    print("== question bank + markdown import ==")
    s, bank = req("POST", "/api/question-banks",
                  {"name": f"E2E Bank {run}", "description": "smoke", "code": f"QB-{run}"}, token=t_tok)
    check("bank create (user-owned)", s in (200, 201), f"{s} {bank}")
    bank_id = bank.get("id")
    md = ("Câu 1 [SINGLE_CHOICE]: 2 + 2 bằng bao nhiêu?\n"
          "*A. 3\nB. 4\nC. 5\nD. 6\n"
          "Lời giải: 2 + 2 = 4\n\n"
          "Câu 2 [TRUE_FALSE_MATRIX]: Đánh dấu đúng/sai.\n"
          "*A. 2 + 2 = 4\nB. 3 + 1 = 5\n*C. 5 - 1 = 4\nD. 1 + 1 = 4\n")
    s, imp = req("POST", f"/api/question-banks/{bank_id}/questions/import-markdown",
                 {"markdown": md}, token=t_tok)
    check("markdown import", s in (200, 201), f"{s} {imp}")
    s, r = req("POST", f"/api/question-banks/{bank_id}/questions/import-markdown",
               {"markdown": md}, token=s_tok)
    check("markdown import denied (not owner)", s in (403, 404), f"{s}")
    s, r = req("GET", f"/api/question-banks/{bank_id}/questions", token=s_tok)
    check("bank questions denied (not owner)", s in (403, 404), f"{s}")

    print("== exam ==")
    s, exam = req("POST", "/api/exams", {"title": f"E2E Exam {run}", "code": f"EX-{run}"}, token=t_tok)
    check("exam create (user-owned, no subject)", s in (200, 201), f"{s} {exam}")
    exam_id = exam.get("id")
    s, qs = req("GET", f"/api/question-banks/{bank_id}/questions", token=t_tok)
    qids = [q["id"] for q in qs.get("items", [])]
    check("bank questions >=2", len(qids) >= 2, f"{len(qids)}")
    s, r = req("PUT", f"/api/exams/{exam_id}/composition",
               {"expectedExamVersion": 0, "durationMinutes": 20, "instructions": "good luck",
                "sections": [{"title": "S1", "position": 0, "instructions": None,
                              "questions": [{"sourceQuestionId": q, "position": i,
                                             "defaultPoints": 10} for i, q in enumerate(qids)]}]},
               token=t_tok)
    check("composition stage (own banks)", s == 200, f"{s} {r if s != 200 else ''}")
    s, r = req("PUT", f"/api/exams/{exam_id}/composition",
               {"expectedExamVersion": 0, "sections": []}, token=s_tok)
    check("composition denied (not owner)", s in (403, 404), f"{s}")
    s, pub = req("POST", f"/api/exams/{exam_id}/publish", {"expectedExamVersion": 1}, token=t_tok)
    check("publish", s == 200 and pub.get("status") == "PUBLISHED", f"{s} {pub}")
    s, r = req("PUT", f"/api/exams/{exam_id}/composition",
               {"expectedExamVersion": 2, "sections": []}, token=t_tok)
    check("published exam immutable", s in (400, 403, 409), f"{s}")

    print("== classroom ==")
    s, cls = req("POST", "/api/classrooms", {"code": f"CLS-{run}", "name": f"E2E Class {run}"}, token=t_tok)
    check("classroom create (teacher-owned)", s in (200, 201), f"{s} {cls}")
    cls_id = cls.get("id")
    s, found = req("GET", "/api/students/search?q=e2estudent", token=t_tok)
    check("student search (teacher)", s == 200 and any(i.get("username") == student for i in found.get("items", [])), f"{s} {found}")
    sid = next((i["userId"] for i in found.get("items", []) if i.get("username") == student), None)
    s, r = req("POST", f"/api/classrooms/{cls_id}/members", {"userIds": [sid]}, token=t_tok)
    check("member add by userId", s in (200, 201) and r.get("added", 0) == 1, f"{s} {r}")
    s, r = req("GET", f"/api/classrooms/{cls_id}/members", token=o_tok)
    check("classroom members denied (not owner)", s in (403, 404), f"{s}")


    print("== CLASS_RESTRICTED session ==")
    s, r = req("GET", "/api/classrooms/my", token=o_tok)
    check("classrooms/my is teacher-scoped (403 for pure STUDENT)", s == 403, f"{s}")
    s, r = req("GET", "/api/classrooms/roster/my", token=o_tok)
    check("outsider roster empty", s == 200, f"{s} {r}")

    start_t = datetime.now(timezone.utc) - timedelta(minutes=1)
    end_t = datetime.now(timezone.utc) + timedelta(hours=1)
    s, sess = req("POST", f"/api/exams/{exam_id}/sessions",
                  {"title": f"Class session {run}", "visibility": "CLASS_RESTRICTED",
                   "startsAt": iso(start_t), "endsAt": iso(end_t),
                   "maxAttempts": 1, "durationMinutes": 15, "classroomIds": [cls_id]}, token=t_tok)
    check("class-restricted session create", s in (200, 201), f"{s} {sess}")
    session_id = sess.get("id")
    s, r = req("POST", f"/api/exam-sessions/{session_id}/schedule", token=t_tok)
    check("schedule class session", s in (200, 201), f"{s} {r}")
    s, avail = req("GET", "/api/exam-sessions/available", token=s_tok)
    ids = [a["sessionId"] for a in avail.get("items", [])]
    check("member sees class session", session_id in ids, f"{session_id} not in {ids}")
    s, avail_o = req("GET", "/api/exam-sessions/available", token=o_tok)
    ids_o = [a["sessionId"] for a in avail_o.get("items", [])]
    check("outsider does NOT see class session", session_id not in ids_o, f"{session_id} in {ids_o}")
    s, elig = req("GET", f"/api/exam-sessions/{session_id}/eligibility", token=s_tok)
    check("eligibility pre-flight (member)", s == 200 and elig.get("state") in ("CAN_START", "CAN_RESUME"), f"{s} {elig}")
    s, elig = req("GET", f"/api/exam-sessions/{session_id}/eligibility", token=o_tok)
    check("eligibility denies outsider", s == 403, f"{s}")

    s, start = req("POST", f"/api/exam-sessions/{session_id}/attempts", {"clientInstanceId": None}, token=s_tok)
    check("attempt start (member)", s in (200, 201), f"{s} {start}")
    attempt_id = start.get("attemptId")
    s, r = req("POST", f"/api/exam-sessions/{session_id}/attempts", {"clientInstanceId": None}, token=o_tok)
    check("attempt start denied (outsider)", s == 403, f"{s}")
    if attempt_id:
        s, det = req("GET", f"/api/attempts/{attempt_id}", token=s_tok)
        qviews = det.get("questions", []) if isinstance(det, dict) else []
        seq = 0
        for qv in qviews:
            qtype = qv.get("questionType")
            opts = qv.get("options") or []
            if qtype == "SINGLE_CHOICE" and opts:
                payload = {"selectedOptionKey": opts[0]["optionKey"]}
            elif qtype == "TRUE_FALSE_MATRIX" and opts:
                payload = {"responses": {o["optionKey"]: (i % 2 == 0) for i, o in enumerate(opts)}}
            else:
                payload = {"value": "2.50"}
            seq += 1
            s, r = req("PUT", f"/api/attempts/{attempt_id}/answers",
                       {"attemptQuestionId": qv["attemptQuestionId"],
                        "answerPayload": payload, "sequenceNumber": seq}, token=s_tok)
            check(f"autosave q{qv['attemptQuestionId']}", s in (200, 201), f"{s} {r}")
        s, sub = req("POST", f"/api/attempts/{attempt_id}/submit",
                     {"submissionIdempotencyKey": f"e2e-{run}"}, token=s_tok)
        check("submit attempt", s in (200, 201), f"{s} {sub}")
        s, res = req("GET", f"/api/attempts/{attempt_id}/result", token=s_tok)
        check("own result visible", s == 200, f"{s}")
        s, res2 = req("GET", f"/api/attempts/{attempt_id}/result", token=o_tok)
        check("foreign result denied", s in (403, 404), f"{s}")

    print("== teacher results ==")
    s, rr = req("GET", f"/api/exam-sessions/{session_id}/results", token=t_tok)
    check("session results (owner)", s == 200 and rr.get("totalElements", 0) == 1, f"{s} {rr}")
    s, st = req("GET", f"/api/exam-sessions/{session_id}/statistics", token=t_tok)
    check("session statistics (owner)", s == 200, f"{s}")
    s, x = req("GET", f"/api/exam-sessions/{session_id}/results/export", token=t_tok)
    check("xlsx export (owner)", s == 200 and isinstance(x, (bytes, bytearray)) and len(x) > 500, f"{s} {type(x)}")
    s, r = req("GET", f"/api/exam-sessions/{session_id}/results", token=o_tok)
    check("results denied (not owner)", s in (403, 404), f"{s}")

    print("== PUBLIC session + sharing ==")
    s, pub_sess = req("POST", f"/api/exams/{exam_id}/sessions",
                      {"title": f"Public session {run}", "visibility": "PUBLIC",
                       "startsAt": iso(start_t), "endsAt": iso(end_t),
                       "maxAttempts": 1, "durationMinutes": 15}, token=t_tok)
    psid = pub_sess.get("id")
    check("public session create", s in (200, 201), f"{s}")
    s, r = req("POST", f"/api/exam-sessions/{psid}/schedule", token=t_tok)
    check("schedule public session", s in (200, 201), f"{s} {r}")
    s, avail_o = req("GET", "/api/exam-sessions/available", token=o_tok)
    ids_o = [a["sessionId"] for a in avail_o.get("items", [])]
    check("outsider sees PUBLIC session", psid in ids_o, f"{psid} not in {ids_o}")
    s, elig = req("GET", f"/api/exam-sessions/{psid}/eligibility", token=o_tok)
    check("share-link preflight PUBLIC", s == 200 and elig.get("state") == "CAN_START", f"{s} {elig}")
    s, start_o = req("POST", f"/api/exam-sessions/{psid}/attempts", {"clientInstanceId": None}, token=o_tok)
    check("outsider starts PUBLIC attempt", s in (200, 201), f"{s}")

    print("== blocked participant ==")
    s, me_o = req("GET", "/api/auth/me", token=o_tok)
    o_uid = me_o.get("id") or me_o.get("userId")
    s, r = req("POST", f"/api/exam-sessions/{psid}/participants/by-user/{o_uid}/block", token=t_tok)
    check("block user (owner)", s == 200, f"{s} {r}")

    print("== decommissioned endpoints ==")
    for path in ["/api/workspaces/my", "/api/schools", "/api/users", "/api/roles",
                 "/api/admin/pending-students"]:
        s, r = req("GET", path, token=t_tok)
        check(f"decommissioned {path}", s in (401, 403, 404), f"{s}")

    print(f"\n==== E2E RESULT: {len(PASSED)} passed, {len(FAILED)} failed ====")
    for name, detail in FAILED:
        print(f"  FAILED: {name} -> {detail}")
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
