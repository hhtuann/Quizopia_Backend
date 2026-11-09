#!/usr/bin/env python3
"""Quizopia P3-C Community Social E2E (#69 gate) against the dockerized stack.

Journeys: guest aggregates + comments read -> login-gated actions -> like
toggle -> rating (self-rating 409, alias collision 409, impersonation 400) ->
comment create/edit/delete + foreign-deny -> comment report -> moderator
hide/restore + tombstone -> ineligible publication exposes nothing -> fork +
bookmark regression.

Run AFTER rebuilding the backend container on feature/p3-c-social:
python e2e_community_social.py   (stack on localhost:8080, Mailpit :8025)
Exit 0 = all checks passed.
"""
import json
import subprocess
import sys
import time
import urllib.request
import urllib.error
import uuid

BASE = "http://localhost:8080"
MAILPIT = "http://localhost:8025"
PSQL = ["docker", "exec", "quizopia_postgres", "psql", "-U", "quiz_user", "-d", "quizopia_db"]
PASSED, FAILED = [], []


def check(name, cond, detail=""):
    (PASSED if cond else FAILED).append((name, detail))
    print(f"  {'PASS' if cond else 'FAIL'} {name}" + ("" if cond else f": {detail}"))


def req(method, path, body=None, token=None):
    r = urllib.request.Request(BASE + path,
                               data=json.dumps(body).encode() if body is not None else None,
                               method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            payload = resp.read()
            ct = resp.headers.get("Content-Type", "")
            return resp.status, (json.loads(payload) if "json" in ct else payload)
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read())
        except Exception:
            return e.code, None


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
    s, _ = req("POST", "/api/auth/register", {
        "username": username, "email": email, "displayName": username.title(),
        "password": "E2ePass!123", "phone": "+84900000001"})
    otp = mailpit_otp(email)
    s, _ = req("POST", "/api/auth/verify-email", {"email": email, "otp": otp})
    s, r = req("POST", "/api/auth/login", {"identifier": username, "password": "E2ePass!123"})
    return r.get("accessToken")


def sql(stmt):
    return subprocess.run(PSQL + ["-tAc", stmt], capture_output=True, text=True).stdout.strip()


def forbidden_keys(node, keys=("answerKey", "isCorrect", "email", "username", "userId", "authorUserId")):
    hits = []

    def walk(n, path="$"):
        if isinstance(n, dict):
            for k, v in n.items():
                if any(t.lower() in k.lower() for t in keys):
                    hits.append(f"{path}.{k}")
                walk(v, f"{path}.{k}")
        elif isinstance(n, list):
            for i, v in enumerate(n):
                walk(v, f"{path}[{i}]")

    walk(node)
    return hits


def main():
    run = uuid.uuid4().hex[:6]
    author, student, forker = f"c3author{run}", f"c3student{run}", f"c3forker{run}"

    print("== setup: users + published publication (ALLOW_FORK) ==")
    a_tok = register_and_verify(author)
    s_tok = register_and_verify(student)
    f_tok = register_and_verify(forker)
    for t in (a_tok, f_tok):
        req("POST", "/api/auth/upgrade-teacher", token=t)
    s, bank, _ = req("POST", "/api/question-banks", {"name": f"C3 Bank {run}"}, token=a_tok) if False else req("POST", "/api/question-banks", {"name": f"C3 Bank {run}"}, token=a_tok)
    md = ("Cau 1 [SINGLE_CHOICE]: 2+2?\n*A. 3\nB. 4\nC. 5\nD. 6\n\n"
          "Cau 2 [NUMERIC_FILL]: pi?\nĐáp án: 3.14\n")
    req("POST", f"/api/question-banks/{bank.get('id')}/questions/import-markdown", {"markdown": md}, token=a_tok)
    s, exam, _ = req("POST", "/api/exams", {"title": f"C3 Exam {run}"}, token=a_tok)
    s, qs, _ = req("GET", f"/api/question-banks/{bank.get('id')}/questions", token=a_tok)
    req("PUT", f"/api/exams/{exam.get('id')}/composition", {
        "expectedExamVersion": 0, "durationMinutes": 10,
        "sections": [{"title": "S1", "position": 0,
                      "questions": [{"sourceQuestionId": q["id"], "position": i, "defaultPoints": 5}
                                    for i, q in enumerate(qs.get("items", []))]}]}, token=a_tok)
    req("POST", f"/api/exams/{exam.get('id')}/publish", {"expectedExamVersion": 1}, token=a_tok)
    s, cats, _ = req("GET", "/api/public/community/categories")
    cat = next((c for c in cats if c.get("code") == "toan"), cats[0])
    s, r, _ = req("POST", "/api/community/publications", {
        "examId": exam.get("id"), "title": f"C3 Quiz {run}", "summary": "social e2e",
        "categoryId": cat["id"], "tags": ["c3"], "authorAlias": f"Prof{run}",
        "reusePolicy": "ALLOW_FORK"}, token=a_tok)
    pub_id = r.get("publicationId")
    req("POST", f"/api/community/publications/{pub_id}/publish", token=a_tok)
    check("publication published", pub_id is not None, f"{r}")

    print("== guest: aggregates + comments readable ==")
    s, summ = req("GET", f"/api/public/community/publications/{pub_id}/social-summary")
    check("guest summary counts-only", s == 200 and summ == {"likeCount": 0, "average": None, "ratingCount": 0, "commentCount": 0}, f"{s} {summ}")
    check("summary no identity keys", not forbidden_keys(summ), str(forbidden_keys(summ)))
    s, comments = req("GET", f"/api/public/community/publications/{pub_id}/comments")
    check("guest comments empty page", s == 200 and comments.get("totalElements") == 0 and comments.get("sort") is not None, f"{s}")
    s, r = req("POST", f"/api/community/publications/{pub_id}/comments", {"body": "hi", "alias": "Guest"})
    check("guest comment 401", s == 401, f"{s}")
    s, r = req("PUT", f"/api/community/publications/{pub_id}/like")
    check("guest like 401", s == 401, f"{s}")

    print("== student: like + rating + comments ==")
    s, r = req("PUT", f"/api/community/publications/{pub_id}/like", token=s_tok)
    check("like 201", s == 201 and r.get("liked") is True and r.get("likeCount") == 1, f"{s} {r}")
    s, r = req("PUT", f"/api/community/publications/{pub_id}/like", token=s_tok)
    check("like idempotent 200", s == 200 and r.get("likeCount") == 1, f"{s}")
    s, r = req("PUT", f"/api/community/publications/{pub_id}/rating", {"rating": 4}, token=s_tok)
    check("rating 201 avg 4.0", s == 201 and r.get("average") == 4.0 and r.get("myRating") == 4, f"{s} {r}")
    s, r = req("PUT", f"/api/community/publications/{pub_id}/rating", {"rating": 5}, token=s_tok)
    check("rating update 200 avg 5.0", s == 200 and r.get("average") == 5.0 and r.get("ratingCount") == 1, f"{s} {r}")
    s, r = req("PUT", f"/api/community/publications/{pub_id}/comments", {"body": "Great quiz!", "alias": f"Stu{run}"}, token=s_tok)
    check("comment 201 with alias", s == 201 and r.get("authorAlias") == f"Stu{run}", f"{s} {r}")
    c1 = r.get("commentId")
    s, r = req("POST", f"/api/community/publications/{pub_id}/comments", {"body": "second without alias"}, token=s_tok)
    check("second comment reuses alias", s == 201 and r.get("authorAlias") == f"Stu{run}", f"{s} {r}")
    c2 = r.get("commentId")
    s, r = req("PATCH", f"/api/community/comments/{c1}", {"body": "Edited!"}, token=s_tok)
    check("own edit 200 + editedAt", s == 200 and r.get("editedAt") is not None, f"{s} {r}")

    print("== self-rating + alias contract ==")
    s, r = req("PUT", f"/api/community/publications/{pub_id}/rating", {"rating": 5}, token=a_tok)
    check("author self-rating 409", s == 409 and isinstance(r, dict) and r.get("code") == "COMMUNITY_SELF_RATING_NOT_ALLOWED", f"{s} {r}")
    s, r = req("POST", f"/api/community/publications/{pub_id}/comments", {"body": "x", "alias": f"c3author{run}"}, token=f_tok)
    check("alias == username rejected 400", s == 400, f"{s} {r}")
    s, r = req("POST", f"/api/community/publications/{pub_id}/comments", {"body": "x", "alias": f"Prof{run}"}, token=f_tok)
    check("alias == publication author rejected 400", s == 400, f"{s} {r}")
    s, r = req("POST", f"/api/community/publications/{pub_id}/comments", {"body": "Forker was here", "alias": f"Twins{run}"}, token=f_tok)
    check("forker alias ok 201", s == 201, f"{s} {r}")
    c3 = r.get("commentId")

    print("== fork + bookmark regression ==")
    key = f"c3-{run}-fork-1"
    s, fork = req("POST", f"/api/community/publications/{pub_id}/fork", token=f_tok) if False else (401, None)
    import urllib.request as _u
    rq = _u.Request(BASE + f"/api/community/publications/{pub_id}/fork", data=json.dumps({}).encode(), method="POST")
    rq.add_header("Content-Type", "application/json")
    rq.add_header("Authorization", "Bearer " + f_tok)
    rq.add_header("Idempotency-Key", key)
    with _u.urlopen(rq, timeout=30) as resp:
        fork = json.loads(resp.read())
        check("fork 201 regression", resp.status == 201 and fork.get("targetExamId"), str(fork)[:100])
    s, r = req("PUT", f"/api/community/publications/{pub_id}/bookmark", token=s_tok)
    check("bookmark regression", s in (200, 201), f"{s}")

    print("== foreign comment deny ==")
    s, r = req("PATCH", f"/api/community/comments/{c1}", {"body": "hijack"}, token=f_tok)
    check("foreign edit 404", s == 404, f"{s}")
    s, r = req("DELETE", f"/api/community/comments/{c1}", token=f_tok)
    check("foreign delete 404", s == 404, f"{s}")

    print("== summary reflects state ==")
    s, summ = req("GET", f"/api/community/publications/{pub_id}/social-summary", token=s_tok)
    check("auth summary states", s == 200 and summ.get("liked") is True and summ.get("myRating") == 5
          and summ.get("commentCount") == 3 and summ.get("likeCount") == 1, f"{summ}")
    s, summ_f = req("GET", f"/api/community/publications/{pub_id}/social-summary", token=f_tok)
    check("other user sees own states only", summ_f.get("liked") is False and summ_f.get("myRating") is None, f"{summ_f}")

    print("== comment report + moderation ==")
    s, rep = req("POST", f"/api/community/comments/{c3}/reports", {"reasonCode": "SPAM", "details": "e2e"}, token=s_tok)
    check("comment report 201", s == 201, f"{s} {rep}")
    s, rep2 = req("POST", f"/api/community/comments/{c3}/reports", {"reasonCode": "SPAM"}, token=s_tok)
    check("comment report dedupe 200", s == 200 and rep2.get("reportId") == rep.get("reportId"), f"{s}")
    role_id = sql("SELECT id FROM roles WHERE code='COMMUNITY_MODERATOR'")
    sql(f"INSERT INTO user_roles (user_id, role_id) SELECT id, {role_id} FROM users WHERE username='{student}' ON CONFLICT DO NOTHING")
    s, queue = req("GET", "/api/community/moderation/comment-reports?status=OPEN", token=s_tok)
    check("moderator comment queue shows report", s == 200 and any(i.get("id") == rep.get("reportId") for i in queue.get("items", [])), f"{s}")
    s, r = req("POST", f"/api/community/moderation/comments/{c3}/hide", {"reason": "e2e hide"}, token=s_tok)
    check("comment hide 200", s == 200, f"{s}")
    s, comments = req("GET", f"/api/public/community/publications/{pub_id}/comments")
    check("hidden comment gone, count 2", comments.get("totalElements") == 2
          and not any(i.get("commentId") == c3 for i in comments.get("items", [])), f"{comments.get('totalElements')}")
    s, summ = req("GET", f"/api/public/community/publications/{pub_id}/social-summary")
    check("public count sync", summ.get("commentCount") == 2, f"{summ}")
    s, hist = req("GET", f"/api/community/moderation/comments/{c3}/history", token=s_tok)
    check("comment history audit", s == 200 and hist and hist[0].get("action") == "COMMENT_HIDE", f"{s} {str(hist)[:80]}")
    s, r = req("POST", f"/api/community/moderation/comments/{c3}/restore", {"reason": "e2e restore"}, token=s_tok)
    check("comment restore 200", s == 200, f"{s}")

    print("== tombstone ==")
    s, r = req("DELETE", f"/api/community/comments/{c2}", token=s_tok)
    check("own delete 200", s == 200, f"{s}")
    body = sql(f"SELECT body FROM community_comments WHERE id={c2}")
    check("tombstone body cleared in DB", body == "", f"[{body}]")
    s, comments = req("GET", f"/api/public/community/publications/{pub_id}/comments")
    check("deleted comment gone", not any(i.get("commentId") == c2 for i in comments.get("items", [])), "")
    s, r = req("DELETE", f"/api/community/comments/{c2}", token=s_tok)
    check("re-delete idempotent 200", s == 200, f"{s}")
    s, r = req("PATCH", f"/api/community/comments/{c2}", {"body": "zombie"}, token=s_tok)
    check("patch deleted 409", s == 409, f"{s}")

    print("== ineligible publication exposes nothing ==")
    req("POST", f"/api/community/publications/{pub_id}/unpublish", token=a_tok)
    for name, path, tok in [
        ("guest summary 404", f"/api/public/community/publications/{pub_id}/social-summary", None),
        ("guest comments 404", f"/api/public/community/publications/{pub_id}/comments", None),
        ("auth summary 404", f"/api/community/publications/{pub_id}/social-summary", s_tok),
        ("like 404", f"/api/community/publications/{pub_id}/like", s_tok),
        ("comment 404", f"/api/community/publications/{pub_id}/comments", s_tok),
        ("fork 404", f"/api/community/publications/{pub_id}/fork", f_tok),
    ]:
        method = "PUT" if "like" in path or "fork" in path else ("POST" if "comment" in path and "comments" == path.split("/")[-1] else "GET")
        s, r = req(method, path, {} if method != "GET" else None, token=tok)
        check(name, s == 404, f"{s}")
    req("POST", f"/api/community/publications/{pub_id}/publish", token=a_tok)

    print("== unlike + rating delete ==")
    s, r = req("DELETE", f"/api/community/publications/{pub_id}/like", token=s_tok)
    check("unlike 200 count 0", s == 200 and r.get("likeCount") == 0, f"{s} {r}")
    s, r = req("DELETE", f"/api/community/publications/{pub_id}/rating", token=s_tok)
    check("rating delete avg null", s == 200 and r.get("average") is None and r.get("ratingCount") == 0, f"{s} {r}")

    print(f"\n== RESULT: {len(PASSED)} passed, {len(FAILED)} failed ==")
    for name, detail in FAILED:
        print(f"  FAILED {name}: {detail}")
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
