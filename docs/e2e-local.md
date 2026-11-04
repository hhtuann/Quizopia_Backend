# Local E2E recipe (Identity-M3/M4)

Reproducible **from source** — no committed jars, no committed secrets. All
values below are throwaway and generated at run time; never point this stack
at a real database or reuse these credentials anywhere.

## 1. Build the backend jar

```bash
./mvnw -B -ntp -DskipTests package
```

## 2. Isolated stack (throwaway containers on a private network)

```bash
docker network create e2enet
docker run -d --name e2e-postgres --network e2enet \
  -e POSTGRES_DB=quizopia_e2e -e POSTGRES_USER=e2e -e POSTGRES_PASSWORD=e2e \
  postgres:17
docker run -d --name e2e-redis --network e2enet redis:7-alpine
docker run -d --name e2e-mailpit --network e2enet -p 8026:8025 axllent/mailpit

# Image built from an UNTRACKED dockerfile + the freshly built jar:
cat > /tmp/e2e.Dockerfile <<'EOF'
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY backend.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
EOF
cp target/backend-0.0.1-SNAPSHOT.jar /tmp/backend.jar
docker build -q -f /tmp/e2e.Dockerfile -t e2e-backend /tmp

# Secrets: generated HERE at run time (os.urandom), never written to the repo.
JWT=$(python -c "import os,base64;print(base64.b64encode(os.urandom(48)).decode())")
AES=$(python -c "import os,base64;print(base64.b64encode(os.urandom(32)).decode())")
docker run -d --name e2e-backend --network e2enet -p 8181:8080 \
  -e DB_URL=jdbc:postgresql://e2e-postgres:5432/quizopia_e2e \
  -e DB_USERNAME=e2e -e DB_PASSWORD=e2e \
  -e REDIS_HOST=e2e-redis -e REDIS_PORT=6379 \
  -e QUIZOPIA_JWT_SECRET_BASE64=$JWT \
  -e QUIZOPIA_DATA_ENCRYPTION_KEY_BASE64=$AES \
  -e QUIZOPIA_ALLOWED_ORIGINS=http://localhost:3100 \
  -e QUIZOPIA_COOKIE_SECURE=false \
  -e QUIZOPIA_SMTP_HOST=e2e-mailpit -e QUIZOPIA_SMTP_PORT=1025 \
  -e "QUIZOPIA_MAIL_FROM=Quizopia <no-reply@quizopia.local>" \
  -e QUIZOPIA_DEMO_DATA_ENABLED=false \
  e2e-backend
```

## 3. Frontend dev server

```bash
npm ci --no-audit --no-fund
NEXT_PUBLIC_API_URL=http://localhost:8181 npm run dev -- -p 3100
```

## 4. OTP retrieval (Mailpit API)

```bash
curl -s "http://localhost:8026/api/v1/messages?limit=1"
```

## 5. Teardown

```bash
docker rm -f e2e-backend e2e-frontend e2e-postgres e2e-redis e2e-mailpit
docker network rm e2enet
```

## Retention policy (M3-03)

Roster terminal entries (REVOKED/DECLINED) and their encrypted sensitive
fields are purged by `ClassroomRosterRetentionScheduler` after
`quizopia.roster.retention.days` days (default `0` = disabled). Policy owner:
platform operator, set per environment. LINKED entries are never purged.
**Consent consequence:** deleting a DECLINED row ends that classroom's
standing opt-out for the address — the opt-out window equals the retention
window, after which a teacher re-invitation can auto-link again.
