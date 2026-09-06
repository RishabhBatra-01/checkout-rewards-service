# backend

The Spring Boot service. See the [repository README](../README.md) for setup, run and
test instructions, and [DECISIONS.md](../DECISIONS.md) for design rationale.

Quick start, from this directory:

```bash
docker compose -f ../docker-compose.yml up -d   # PostgreSQL on localhost:5432
cp .env.example .env                            # already matches those settings
./mvnw spring-boot:run                          # serves on http://localhost:8080
```

`./mvnw test` needs none of that — 69 tests run against a PostgreSQL that
Testcontainers starts and throws away.
