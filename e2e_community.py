#!/usr/bin/env python3
"""Quizopia Community MVP E2E (P3 gate #49) against the dockerized stack.

Journeys: guest discovery -> teacher publish (ALLOW_FORK consent) -> student
bookmark/report -> cross-user fork (idempotent) -> target edit+publish ->
moderator grant via runbook SQL -> hide/restore/audit -> author unpublish ->
disabled publisher disappearance -> anti-enumeration + no-answer-leak checks.

Run: python e2e_community.py   (stack on localhost:8080, Mailpit :8025)
Exit 0 = all checks passed.
"""
import json
import subprocess
import sys
import time
import urllib.request
import urllib.error
import uuid
PSQL = ["docker", "exec", "quizopia_postgres", "psql", "-U", "quiz_user", "-d", "quizopia_db"]

BASE = "http://localhost:8080"
MAILPIT = "http://localhost:8025"

PASSED, FAILED = [], []

FORBIDDEN_KEYS = {"answerKey", "isCorrect", "answer_key", "is_correct", "examId",
                  "sourceExamId", "questionId", "optionKey", "email", "username",
                  "publisherUserId", "displayName", "passwordHash"}


def check(name, cond, detail=""):
    (PASSED if cond else FAILED).append((name, detail))
    print(f"  {'PASS' if cond else 'FAIL'} {name}" + ("" if cond else f": {detail}"))


def req(method, path, body=None, token=None, key=None):
    r = urllib.request.Request(BASE + path,
                               data=json.dumps(body).encode() if body is not None else None,
                               method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    if key:
        r.add_header("Idempotency-Key", key)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            payload = resp.read()
            hdrs = dict(resp.headers)
            ct = resp.headers.get("Content-Type", "")
            return resp.status, (json.loads(payload) if "json" in ct else payload), hdrs
    except urllib.error.HTTPError as e:
        payload = e.read()
        try:
            return e.code, json.loads(payload), dict(e.headers)
        except Exception:
            return e.code, payload, dict(e.headers)


def mailpit_otp(to_email):
    for _ in range(30):
        try:
            with urllib.request.urlopen(MAILPIT + "/api/v1/messages?limit=20", timeout=5) as r:
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
    s, r, _ = req("POST", "/api/auth/register", {
        "username": username, "email": email, "displayName": username.title(),
        "password": "E2ePass!123", "phone": "+84900000001"})
    check(f"register {username}", s in (200, 201), f"{s} {r}")
    otp = mailpit_otp(email)
    check(f"otp mail {username}", otp is not None)
    s, r, _ = req("POST", "/api/auth/verify-email", {"email": email, "otp": otp})
    check(f"verify {username}", s in (200, 201), f"{s} {r}")
    s, r, _ = req("POST", "/api/auth/login", {"identifier": username, "password": "E2ePass!123"})
    check(f"login {username}", s == 200 and r.get("accessToken"), f"{s}")
    return r.get("accessToken")


def forbidden_scan(node, path="$"):
    hits = []
    if isinstance(node, dict):
        for k, v in node.items():
            if k in FORBIDDEN_KEYS:
                hits.append(f"{path}.{k}")
            hits += forbidden_scan(v, f"{path}.{k}")
    elif isinstance(node, list):
        for i, v in enumerate(node):
            hits += forbidden_scan(v, f"{path}[{i}]")
    return hits


def sql(stmt):
    return subprocess.run(PSQL + ["-tAc", stmt], capture_output=True, text=True).stdout.strip()


def main():
    run = uuid.uuid4().hex[:6]
    author, student, forker = f"p3author{run}", f"p3student{run}", f"p3forker{run}"
    answers = {"numeric": "3.14", "secret-answer-token": "correct-horse"}

    print("== users ==")
    a_tok = register_and_verify(author)
    s_tok = register_and_verify(student)
    f_tok = register_and_verify(forker)
    for t, n in ((a_tok, author), (f_tok, forker)):
        s, r, _ = req("POST", "/api/auth/upgrade-teacher", token=t)
        check(f"teacher upgrade {n}", s in (200, 201), f"{s}")

    print("== author content: bank (4 types) -> exam -> publish ==")
    s, bank, _ = req("POST", "/api/question-banks", {"name": f"P3 Bank {run}"}, token=a_tok)
    check("bank create", s in (200, 201), f"{s}")
    bank_id = bank.get("id")
    md = (
        f"Câu 1 [SINGLE_CHOICE]: 2 + 2? (token {answers['secret-answer-token']})\n"
        "*A. 3\nB. 4\nC. 5\nD. 6\nLời giải: 4\n\n"
        "Câu 2 [MULTIPLE_CHOICE]: Chọn các số nguyên tố?\n"
        "*A. 2\nB. 4\n*C. 5\nD. 9\n\n"
        "Câu 3 [TRUE_FALSE_MATRIX]: Đúng hay sai?\n"
        "*A. 2+2=4\nB. 3+1=5\n*C. 5-1=4\nD. 1+1=4\n\n"
        f"Câu 4 [NUMERIC_FILL]: pi làm tròn 2 số thập phân?\nĐáp án: {answers['numeric']}\n"
    )
    s, imp, _ = req("POST", f"/api/question-banks/{bank_id}/questions/import-markdown",
                    {"markdown": md}, token=a_tok)
    check("markdown import 4 types", s in (200, 201), f"{s} {imp if s not in (200, 201) else ''}")
    s, exam, _ = req("POST", "/api/exams", {"title": f"P3 Exam {run}"}, token=a_tok)
    exam_id = exam.get("id")
    s, qs, _ = req("GET", f"/api/question-banks/{bank_id}/questions", token=a_tok)
    qids = [q["id"] for q in qs.get("items", [])]
    check("4 questions imported", len(qids) == 4, f"{len(qids)}")
    s, r, _ = req("PUT", f"/api/exams/{exam_id}/composition", {
        "expectedExamVersion": 0, "durationMinutes": 25,
        "sections": [{"title": "S1", "position": 0,
                      "questions": [{"sourceQuestionId": q, "position": i, "defaultPoints": 10}
                                    for i, q in enumerate(qids)]}]}, token=a_tok)
    check("composition", s == 200, f"{s}")
    s, pub, _ = req("POST", f"/api/exams/{exam_id}/publish", {"expectedExamVersion": 1}, token=a_tok)
    check("exam PUBLISHED", s == 200 and pub.get("status") == "PUBLISHED", f"{s}")

    print("== community publish (consent alias + ALLOW_FORK) ==")
    s, cats, _ = req("GET", "/api/public/community/categories")
    cat = next((c for c in cats if c.get("code") == "toan"), cats[0] if cats else None)
    check("categories public [{id,code,title}]", s == 200 and cat and {"id", "code", "title"} <= set(cat), f"{s}")
    s, r, _ = req("POST", "/api/community/publications", {
        "examId": exam_id, "title": f"Community Quiz {run}", "summary": "e2e smoke",
        "categoryId": cat["id"], "tags": ["algebra", "geometry"], "authorAlias": "Prof E2E",
        "reusePolicy": "ALLOW_FORK"}, token=a_tok)
    check("publication create", s in (200, 201), f"{s} {r}")
    pub_id = r.get("publicationId") or r.get("id")
    s, r, _ = req("POST", f"/api/community/publications/{pub_id}/publish", token=a_tok)
    check("publication PUBLISHED", s == 200 and r.get("status") == "PUBLISHED", f"{s} {r}")
    s, r, _ = req("GET", "/api/community/me/publications", token=a_tok)
    check("owner list paged envelope", s == 200 and isinstance(r.get("items"), list)
          and r["items"][0].get("openReportCount") == 0, f"{s} {str(r)[:120]}")
    s, r, _ = req("POST", "/api/community/publications", {
        "examId": exam_id, "title": "dup", "authorAlias": "x"}, token=a_tok)
    check("duplicate publication 409", s == 409, f"{s}")

    print("== guest discovery ==")
    s, page, h = req("GET", f"/api/public/community/publications?q=Community+Quiz+{run}&tags=algebra&tags=geometry&page=0&size=10")
    mine = [i for i in page.get("items", []) if i.get("publicationId") == pub_id]
    check("guest catalog q+tags AND", s == 200 and len(mine) == 1, f"{s} {len(page.get('items', []))}")
    check("card allowlist + no forbidden keys", not forbidden_scan(page), str(forbidden_scan(page))[:200])
    check("authorName = alias", mine and mine[0].get("authorName") == "Prof E2E", str(mine[:1]))
    check("no-store header", h.get("Cache-Control") == "no-store", h.get("Cache-Control"))
    s, detail, h = req("GET", f"/api/public/community/publications/{pub_id}")
    check("guest detail", s == 200 and detail.get("allowFork") is True, f"{s}")
    check("detail allowlist", not forbidden_scan(detail), str(forbidden_scan(detail))[:200])
    check("detail no-store", h.get("Cache-Control") == "no-store")
    raw = json.dumps(detail)
    check("no answer material in payload", answers["numeric"] not in raw and "expectedAnswer" not in raw
          and answers["secret-answer-token"] not in raw)
    s, tags, _ = req("GET", "/api/public/community/tags")
    check("tags endpoint sees ours", s == 200 and any(t.get("label") == "algebra" for t in tags), f"{s}")
    s, r1, _ = req("GET", "/api/public/community/publications/999999999")
    s2, r2, _ = req("GET", f"/api/public/community/publications/{pub_id+1000}")
    check("anti-enumeration identical 404", s == 404 and s2 == 404 and r1.get("code") == r2.get("code"), f"{s}/{s2}")
    s, r, _ = req("POST", "/api/public/community/publications", {})
    check("guest non-GET on public path denied", s in (401, 403), f"{s}")
    s, r, _ = req("GET", "/api/community/me/publications")
    check("guest on authed route 401", s == 401, f"{s}")
    s, r, _ = req("GET", "/api/public/community/publications/abc")
    check("malformed id 400 not 500", s == 400, f"{s}")

    print("== student bookmark + report ==")
    s, r, _ = req("PUT", f"/api/community/publications/{pub_id}/bookmark", token=s_tok)
    check("bookmark put", s in (200, 201), f"{s}")
    s, r, _ = req("PUT", f"/api/community/publications/{pub_id}/bookmark", token=s_tok)
    check("bookmark put idempotent", s in (200, 201), f"{s}")
    s, r, _ = req("GET", "/api/community/me/bookmarks", token=s_tok)
    check("bookmark list card+savedAt", s == 200 and r.get("items")
          and r["items"][0].get("savedAt") and r["items"][0].get("unavailable") is False, f"{s}")
    s, rep1, _ = req("POST", f"/api/community/publications/{pub_id}/reports",
                     {"reasonCode": "SPAM", "details": "e2e report"}, token=s_tok)
    s2, rep2, _ = req("POST", f"/api/community/publications/{pub_id}/reports",
                      {"reasonCode": "SPAM"}, token=s_tok)
    check("report create + dedupe", s in (200, 201) and s2 == 200 and rep1.get("id") == rep2.get("id"),
          f"{s}/{s2}")

    print("== cross-user fork (teacher B) ==")
    key = f"e2e-{run}-fork-key-0001"
    s, fork, _ = req("POST", f"/api/community/publications/{pub_id}/fork", token=f_tok, key=key)
    check("fork 201", s == 201 and fork.get("targetExamId"), f"{s} {str(fork)[:150]}")
    check("fork attribution", fork.get("sourceTitle") == f"Community Quiz {run}"
          and fork.get("authorAlias") == "Prof E2E", str(fork)[:150])
    target = fork.get("targetExamId")
    s, again, _ = req("POST", f"/api/community/publications/{pub_id}/fork", token=f_tok, key=key)
    check("fork replay 200 same target", s == 200 and again.get("targetExamId") == target, f"{s}")
    s, r, _ = req("POST", f"/api/community/publications/{pub_id}/fork", token=f_tok, key="x" * 9)
    check("different key = second fork", s == 201 and r.get("targetExamId") != target, f"{s}")
    s, r, _ = req("POST", f"/api/community/publications/{pub_id}/fork", token=s_tok, key="student-key-1")
    check("student fork 403", s == 403, f"{s}")
    s, r, _ = req("POST", f"/api/community/publications/{pub_id}/fork", token=f_tok, key="bad")
    check("key too short 400", s == 400, f"{s}")

    print("== fork target editable + publishable ==")
    s, tdet, _ = req("GET", f"/api/exams/{target}", token=f_tok)
    check("target DRAFT owned by forker", s == 200 and tdet.get("status") == "DRAFT", f"{s}")
    tver = tdet.get("examVersion")
    s, r, _ = req("PUT", f"/api/exams/{target}",
                  {"expectedExamVersion": tver, "title": f"Forked {run} v2", "description": "forked"},
                  token=f_tok)
    check("target metadata edit", s == 200, f"{s} {str(r)[:120] if s != 200 else ''}")
    s, r, _ = req("POST", f"/api/exams/{target}/publish", {"expectedExamVersion": tver + 1}, token=f_tok)
    check("target PUBLISH via existing API", s == 200 and r.get("status") == "PUBLISHED", f"{s} {str(r)[:120]}")

    print("== NO_FORK policy ==")
    s, r, _ = req("PATCH", f"/api/community/publications/{pub_id}",
                  {"expectedVersion": 1, "reusePolicy": "NO_FORK"}, token=a_tok)
    check("patch to NO_FORK", s == 200, f"{s}")
    s, r, _ = req("POST", f"/api/community/publications/{pub_id}/fork", token=f_tok, key=f"e2e-{run}-nf")
    check("NO_FORK fork 403", s == 403 and r.get("code") == "COMMUNITY_FORK_NOT_ALLOWED", f"{s} {r if isinstance(r, dict) else ''}")
    s, r, _ = req("PATCH", f"/api/community/publications/{pub_id}",
                  {"expectedVersion": 2, "reusePolicy": "ALLOW_FORK"}, token=a_tok)

    print("== moderator via runbook SQL ==")
    op_id = sql("SELECT id FROM users WHERE username='" + author + "'")
    # dedicated operator account (role-less, ACTIVE) per runbook
    sql(f"INSERT INTO users (username, email, display_name, password_hash, status, local_credential_state, login_requires_email_verification) "
        f"VALUES ('p3op{run}', 'p3op{run}@e2e.local', 'P3 Operator', 'x', 'ACTIVE', 'DISABLED', false) "
        f"ON CONFLICT DO NOTHING")
    op_id = sql(f"SELECT id FROM users WHERE username='p3op{run}'")
    role_id = sql("SELECT id FROM roles WHERE code='COMMUNITY_MODERATOR'")
    sql(f"INSERT INTO user_roles (user_id, role_id) VALUES ({op_id}, {role_id}) ON CONFLICT DO NOTHING")
    sql(f"INSERT INTO community_moderation_actions (actor_user_id, action, reason, to_moderation_state) "
        f"VALUES ({op_id}, 'MODERATOR_GRANTED', 'e2e provisioning', 'VISIBLE')")
    s, tok_op, _ = None, None, None
    # operator cannot log in (DISABLED credential) -> grant moderator to the STUDENT instead (runbook grants any ACTIVE user)
    sql(f"INSERT INTO user_roles (user_id, role_id) SELECT id, {role_id} FROM users WHERE username='{student}' ON CONFLICT DO NOTHING")
    sql(f"INSERT INTO community_moderation_actions (actor_user_id, action, reason, to_moderation_state) "
        f"SELECT id, 'MODERATOR_GRANTED', 'e2e provisioning via operator', 'VISIBLE' FROM users WHERE username='p3op{run}'")
    s, r, _ = req("GET", "/api/community/moderation/grant", token=s_tok)
    check("grant endpoint 200 for granted student", s == 200 and r.get("granted") is True, f"{s}")
    s, r, _ = req("GET", "/api/community/moderation/grant", token=f_tok)
    check("grant endpoint 403 for non-moderator", s == 403, f"{s}")
    s, r, _ = req("GET", "/api/community/moderation/reports?status=OPEN", token=s_tok)
    check("queue shows our OPEN report", s == 200 and any(i.get("id") == rep1.get("reportId") for i in r.get("items", [])), f"{s}")
    s, r, _ = req("POST", f"/api/community/moderation/publications/{pub_id}/hide", {"reason": "e2e hide"}, token=s_tok)
    check("hide 200", s == 200, f"{s}")
    s, r, _ = req("GET", f"/api/public/community/publications/{pub_id}")
    check("hidden -> guest 404", s == 404, f"{s}")
    s, r, _ = req("GET", "/api/community/me/bookmarks", token=s_tok)
    entry = next((i for i in r.get("items", []) if i.get("publicationId") == pub_id), None)
    check("bookmark unavailable placeholder", entry and entry.get("unavailable") is True
          and set(entry.keys()) == {"publicationId", "savedAt", "unavailable"}, str(entry))
    s, r, _ = req("POST", f"/api/community/publications/{pub_id}/unpublish", token=a_tok)
    check("author unpublish while hidden ok", s == 200, f"{s}")
    s, r, _ = req("POST", f"/api/community/publications/{pub_id}/publish", token=a_tok)
    check("author republish blocked while HIDDEN", s == 409, f"{s}")
    s, hist, _ = req("GET", f"/api/community/moderation/publications/{pub_id}/history", token=s_tok)
    check("history rows (actor ids only)", s == 200 and hist and hist[0].get("actorUserId"), f"{s} {str(hist)[:80]}")
    check("history no forbidden keys", not forbidden_scan(hist), str(forbidden_scan(hist))[:200])
    s, r, _ = req("POST", f"/api/community/moderation/publications/{pub_id}/restore", {"reason": "e2e restore"}, token=s_tok)
    check("restore 200 (stays UNPUBLISHED)", s == 200 and r.get("status") == "UNPUBLISHED", f"{s} {str(r)[:100]}")
    sql(f"DELETE FROM user_roles WHERE user_id=(SELECT id FROM users WHERE username='{student}') AND role_id={role_id}")
    sql(f"INSERT INTO community_moderation_actions (actor_user_id, action, reason) SELECT id, 'MODERATOR_REVOKED', 'e2e revoke' FROM users WHERE username='p3op{run}'")
    s, r, _ = req("GET", "/api/community/moderation/reports?status=OPEN", token=s_tok)
    check("revoked grant immediate 403", s == 403, f"{s}")

    print("== republish + disabled publisher disappearance ==")
    s, r, _ = req("POST", f"/api/community/publications/{pub_id}/publish", token=a_tok)
    check("republish after restore", s == 200 and r.get("status") == "PUBLISHED", f"{s}")
    sql(f"UPDATE users SET status='DISABLED' WHERE username='{author}'")
    s, r, _ = req("GET", "/api/auth/me", token=a_tok)
    check("disabled author token 401 (live AuthN)", s == 401, f"{s}")
    s, page, _ = req("GET", f"/api/public/community/publications?q=Community+Quiz+{run}")
    check("disabled publisher gone from catalog",
          not any(i.get("publicationId") == pub_id for i in page.get("items", [])), f"{len(page.get('items', []))} items")
    s, r, _ = req("GET", f"/api/public/community/publications/{pub_id}")
    check("disabled publisher detail 404", s == 404, f"{s}")

    print(f"\n== RESULT: {len(PASSED)} passed, {len(FAILED)} failed ==")
    for name, detail in FAILED:
        print(f"  FAILED {name}: {detail}")
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
