# backend

The Spring Boot service. See the [repository README](../README.md) for setup, run and
test instructions, and [DECISIONS.md](../DECISIONS.md) for design rationale.

Quick start, from this directory:

```bash
cp .env.example .env      # fill in your Supabase connection details
./mvnw spring-boot:run    # serves on http://localhost:8080
./mvnw test               # 63 tests against a Testcontainers PostgreSQL
```
