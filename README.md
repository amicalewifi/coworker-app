# coworker-app

Member portal and ops console for **l'Amicale du WiFi**, a Swiss coworking
space. Spring Boot 3 + Thymeleaf web app that handles member accounts,
day-pass and conference-room purchases (via Zahls payments), printer credit,
WiFi access provisioning (UniFi + Akuvox door intercom), and admin reporting.

Public deployment: <https://coworker.amicalewifi.ch>

## Stack

| Layer | Choice |
| --- | --- |
| Language / runtime | Java 21 |
| Framework | Spring Boot 3.2 (Web, Security, Data JPA, Thymeleaf, Actuator, Validation) |
| Build | Gradle (`./gradlew bootJar`) |
| Database | PostgreSQL 16, schema migrated by Flyway (`src/main/resources/db/migration/V*.sql`) |
| Frontend | Server-rendered Thymeleaf + small CSS, no JS framework |
| Reverse proxy | Caddy 2 (auto-TLS via Let's Encrypt) |
| Containerization | Multi-stage `Dockerfile`, orchestrated via `docker-compose.yml` |
| External integrations | Zahls (payments), UniFi (network access), Akuvox (door intercom) |

## Repository layout

```
.
├── Dockerfile                         # multi-stage: gradle build → jre runtime
├── docker-compose.yml                 # full local/prod stack
├── build.gradle / settings.gradle     # Gradle build
├── ops/
│   ├── Caddyfile                      # reverse proxy config
│   ├── .env.example                   # template for runtime secrets
│   └── README.md                      # pointer to coworker-deploy
├── docker/
│   └── init.sql                       # Postgres bootstrap
└── src/main/
    ├── java/ch/amicalewifi/
    │   ├── config/        controller/  model/
    │   ├── repository/    security/    service/
    │   ├── AmicaleApplication.java
    │   └── StartupLogger.java
    └── resources/
        ├── application.yml            # Spring config (Flyway, JPA, integrations)
        ├── db/migration/V*.sql        # Flyway migrations
        ├── templates/                 # Thymeleaf views (auth, admin, mobile, cafeteria)
        └── static/                    # CSS, images
```

## Local development

Prerequisites: Docker, Docker Compose, Java 21 (only if you want to run the
JAR outside the container).

```bash
# 1) Copy the secrets template and fill in values for the integrations you
#    need (you can leave AMICALE_*_API_KEY blank if you don't need to test
#    Zahls / UniFi / Akuvox locally).
cp ops/.env.example .env
$EDITOR .env

# 2) Bring the stack up. First run builds the JAR inside the image (~2 min).
docker compose up -d --build

# 3) App: http://localhost  (Caddy in the stack will redirect to HTTPS;
#    for local you usually hit the app directly via the container)
docker compose logs -f app
```

To run the app outside Docker against the dockerized Postgres:

```bash
docker compose up -d postgres
./gradlew bootRun
# app on http://localhost:8081
```

Tests:

```bash
./gradlew test
```

## Database migrations

All schema changes are Flyway migrations under
`src/main/resources/db/migration/`. Naming: `V<n>__short_description.sql`.

- Migrations are applied at app startup (`spring.flyway.enabled: true`).
- Once a migration has been applied to prod, **never modify or delete it** —
  Flyway validates the checksum and refuses to start if it changes. To
  reverse a previous migration, add a new `V<n+1>__…` instead.
- Placeholders (`${foo}`) can be supplied via `spring.flyway.placeholders.*`
  in `application.yml`, or via `SPRING_FLYWAY_PLACEHOLDERS_FOO` env vars.

## Configuration

Runtime configuration comes from two sources:

1. `application.yml` — defaults baked into the JAR.
2. Environment variables (typically loaded from `.env` by Docker Compose) —
   override the defaults via Spring's relaxed binding. See `ops/.env.example`
   for the full list (Postgres, pgAdmin, Zahls callbacks, UniFi/Akuvox keys).

`.env` is gitignored. Production values live only on the VPS.

## Production / deployment

Provisioning, deployment, backups, and operational runbooks live in a
**separate private** repo:
[`amicalewifi/coworker-deploy`](https://github.com/amicalewifi/coworker-deploy).

That keeps VPS layout, deploy procedures, and any future encrypted secrets
out of this public repo. See `ops/README.md` for a short pointer.

## License

Licensed under the [Apache License, Version 2.0](LICENSE). © 2026 Amicale du WiFi.
