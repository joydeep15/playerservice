# Backend Java Player Service Knowledge Base

## 1. What This Repo Actually Contains

This repository is not one single fully connected application. It currently contains two separate backends plus shared baseball-player data:

1. A Spring Boot service in `src/main/java/...` that:
   - boots an in-memory H2 database from `Player.csv`
   - exposes read-only player lookup endpoints
   - exposes a very small Ollama-backed chat endpoint
2. A Python Flask service in `player-service-model/` that:
   - loads a pre-trained nearest-neighbor model from `team_model.joblib`
   - generates "similar player" teams from engineered player features
   - accepts in-memory feedback exclusions
   - includes placeholder LLM endpoints

Important reality check:

- The Java service does **not** call the Python model service anywhere.
- The Python model service is a separate prototype/service boundary, not an active dependency of the Java app.
- The README focuses mostly on the Java + Ollama path and does not explain the Python sidecar as part of the main runtime.

## 2. Repo Map

### Root

- `pom.xml`
  - Maven build for the Spring Boot app.
- `Player.csv`
  - Main raw player dataset used to seed the Java app database.
- `README.md`
  - Setup guide for the Java service and Ollama.
- `collection/`
  - Example request files for manual endpoint testing.
- `src/main/java/com/app/playerservicejava/`
  - Java source code.
- `src/main/resources/application.yml`
  - Spring Boot runtime configuration.
- `src/main/resources/schema.sql`
  - SQL that creates the H2 table by reading `Player.csv`.
- `player-service-model/`
  - Separate Python model service and training artifacts.

### Java App Packages

- `controller/`
  - REST endpoints for players.
- `controller/chat/`
  - REST endpoints for Ollama integration.
- `service/`
  - Business logic for player retrieval.
- `service/chat/`
  - Business logic for Ollama access.
- `repository/`
  - Spring Data JPA repository for `Player`.
- `model/`
  - JPA entity and response wrapper classes.
- `config/`
  - Bean wiring for Ollama and `RestTemplate`.

### Python Model Service

- `player-service-model/a4a_model/server.py`
  - Flask API and most of the actual Python-side behavior.
- `player-service-model/a4a_model/team_model.joblib`
  - Pre-trained nearest-neighbor model artifact.
- `player-service-model/a4a_model/features_db.csv`
  - Player dataset plus engineered numeric features used by the model.
- `player-service-model/a4a_model/train.ipynb`
  - Notebook used to build the feature dataset and save the model.
- `player-service-model/pyproject.toml`
  - Python dependencies managed by Poetry.
- `player-service-model/Dockerfile`
  - Container image for the Flask model service.

## 3. High-Level Runtime Architecture

### Java Service

```text
HTTP request
  -> Spring MVC controller
  -> service layer
  -> Spring Data JPA repository
  -> H2 in-memory DB
  -> JSON response
```

For chat:

```text
HTTP request
  -> ChatController
  -> ChatClientService
  -> OllamaAPI bean
  -> local Ollama server at http://127.0.0.1:11434/
  -> JSON/plain string response
```

### Python Model Service

```text
HTTP request
  -> Flask route
  -> Pydantic request validation
  -> preloaded nearest-neighbor model + feature dataframe
  -> computed response
  -> JSON response
```

There is currently **no bridge** between these two runtime flows.

## 4. Java App Deep Dive

### 4.1 Bootstrapping

`PlayerServiceJavaApplication` is a standard `@SpringBootApplication` entrypoint with no custom startup logic.

The real bootstrap behavior comes from Spring config and SQL init:

- `application.yml`
  - H2 in-memory datasource: `jdbc:h2:mem:playerdb`
  - Hibernate DDL generation disabled: `ddl-auto: none`
  - H2 console enabled
  - server port `8080`
- `schema.sql`
  - drops `PLAYERS` if it exists
  - recreates `PLAYERS` from `CSVREAD('Player.csv')`

This means the app starts with a fresh in-memory database every run and repopulates it from the CSV.

### 4.2 Startup Dependency on Filesystem Layout

The database seed uses:

```sql
CREATE TABLE PLAYERS AS SELECT * FROM CSVREAD('Player.csv');
```

That path is a filesystem-relative path, not a classpath resource lookup.

Practical consequence:

- The Java app expects `Player.csv` to be present in the process working directory when startup SQL runs.
- Running from the repo root is the intended path.
- Packaging/running the jar from some other directory may fail unless the CSV is also present there.

This is one of the most important operational gotchas in the repo.

### 4.3 Data Model

`Player` maps directly to the `PLAYERS` table created from the CSV.

Observed fields:

- identity: `playerId`
- birth metadata
- death metadata
- names
- physical stats: `weight`, `height`
- handedness: `bats`, `throwStats`
- career dates: `debut`, `finalGame`
- reference ids: `retroId`, `bbrefId`

Important design detail:

- The CSV already contains `PLAYERID`.
- The entity still annotates `playerId` with `@GeneratedValue(strategy = GenerationType.UUID)`.
- That generation strategy is irrelevant for current read-only behavior, but it is conceptually wrong for imported fixed IDs and would become problematic if insert/create endpoints were ever added.

Everything is modeled as `String`, including fields that are naturally numeric or date-like. That keeps ingestion simple but pushes typing/validation concerns out of the domain model.

### 4.4 Repository Layer

`PlayerRepository` extends `JpaRepository<Player, String>`.

There are no custom queries. The service uses only:

- `findAll()`
- `findById(id)`

### 4.5 Service Layer

`PlayerService` contains two methods:

- `getPlayers()`
  - fetches all players from JPA
  - copies them into a `Players` wrapper object
- `getPlayerById(playerId)`
  - calls `findById`
  - introduces a random sleep of up to 2 seconds
  - returns `Optional.empty()` on exception

That delay is explicitly labeled as a "simulated network delay". It is artificial behavior, not a real dependency.

Implications:

- `GET /v1/players/{id}` has intentionally inconsistent latency.
- The endpoint may feel slow even though the lookup is just an in-memory DB call.

### 4.6 Controller Layer

#### `PlayerController`

Base path: `v1/players`

Endpoints:

- `GET /v1/players`
  - returns all players wrapped as:
    ```json
    {
      "players": [...]
    }
    ```
- `GET /v1/players/{id}`
  - returns a single player if found
  - returns `404` if absent

Behavior notes:

- No pagination, filtering, sorting, or search.
- Returning the full dataset means the app will serialize the entire CSV-backed table in one response.
- With roughly 19k records, it is still manageable, but it is not a pattern that scales.

#### `ChatController`

Base path: `v1/chat`

Endpoints:

- `POST /v1/chat`
  - returns a string from Ollama
- `GET /v1/chat/list-models`
  - returns the list of models known to the local Ollama server

Critical mismatch with the example request file:

- `collection/chat_requests.txt` shows a body containing `model` and `prompt`
- the Java `POST /v1/chat` endpoint does **not** accept or read a request body
- the controller simply calls `chatClientService.chat()` with no input

So the current implementation always asks Ollama for the same hard-coded prompt.

### 4.7 Chat Integration Internals

`ChatClientConfiguration` defines:

- `RestTemplate` bean
- `OllamaAPI` bean pointed at `http://127.0.0.1:11434/`
- request timeout set to 120 seconds

`RestTemplate` is currently unused in the repo.

`ChatClientService` uses the `OllamaAPI` bean and hard-codes:

- model: `tinyllama`
- prompt: `"Recite a haiku about recursion."`

So the chat endpoint is best understood as a connectivity/demo endpoint, not a general chat API.

Operational requirements:

- Ollama must be running locally on port `11434`
- the `tinyllama` model must be available in that local Ollama instance

Failure mode:

- If Ollama is unavailable, checked exceptions bubble out of controller methods and the request will fail with a server error
- There is no custom exception mapping or graceful degradation

## 5. Python Model Service Deep Dive

### 5.1 What It Is

The Python service is a separate Flask app that loads:

- `team_model.joblib`
- `features_db.csv`

at import time, before requests are served.

It is packaged to run on port `5000` via the provided Dockerfile.

### 5.2 What Problem It Solves

The Python service appears to generate a "team" of similar players based on:

- an existing player ID (`seed_id`), or
- a custom feature vector (`birth_year`, `height`, `weight`, `bats`, `throws`)

Internally it uses a nearest-neighbor model trained on engineered player features.

### 5.3 Feature Engineering and Training Artifacts

The notebook in `train.ipynb` shows the model prep pipeline:

- read `player.csv`
- derive `birthZ`
  - z-score of a fractional birth date approximation from year/month/day
- derive `weightZ`
- derive `heightZ`
- encode bats:
  - `R -> 1.0`
  - `L -> -1.0`
  - other/missing -> `0.0`
- encode throws the same way
- fit `NearestNeighbors(n_neighbors=25)`
- save:
  - `team_model.joblib`
  - `features_db.csv`

The serving code uses these five features:

- `birthZ`
- `heightZ`
- `weightZ`
- `batsN`
- `throwsN`

### 5.4 Request/Response Behavior

#### `/team/generate`

Input model:

- `seed_id` or `features` must be provided
- `team_size` is required

Behavior:

- loads seed features either from the dataframe row or from supplied feature values
- calls `nn_model.kneighbors(...)`
- returns a list of player IDs from nearest-neighbor lookup

Important details:

- The seed player is usually included in the returned member list because the nearest-neighbor lookup is run against the same dataset that contains the seed.
- The service tracks exclusions per seed in an in-memory dictionary `exclude_db`.
- When exclusions exist, it requests more neighbors than the caller asked for, then filters excluded members out.

Simulated instability is intentionally built in:

- 1% chance: raises `TimeoutError`
- next 1% chance: sleeps 6 seconds

This looks like demo/failure-injection behavior rather than production design.

#### `/team/feedback`

Input:

- `seed_id`
- `member_id`
- `feedback`
- `prediction_id`

Behavior:

- validates that `seed_id` exists in the known player set
- if feedback is negative, stores `member_id` in `exclude_db[seed_id]`
- returns whether the feedback was accepted

Important limitations:

- feedback state is in memory only
- restarting the service loses exclusions
- there is no persistence, audit trail, or concurrency protection

### 5.5 Placeholder LLM Endpoints

`server.py` also defines:

- `POST /llm/generate`
- `POST /llm/feedback`

These are not real model endpoints yet.

They are placeholders and have multiple implementation mismatches:

- declared output models do not match the actual JSON payloads
- route signatures are not consistent with the request-validation style used above
- the logic does not call any real model

Treat them as scaffolding, not working product functionality.

### 5.6 Docker and Packaging

The Dockerfile:

- uses `python:3.11`
- installs Poetry
- copies the full `player-service-model/` project
- installs dependencies with Poetry into the global environment
- sets working directory to `a4a_model`
- starts `python server.py`

This makes the Python service runnable in isolation, but again, nothing in the Java app calls it.

## 6. Data Assets

### `Player.csv`

- Primary raw dataset for the Java app
- 19,371 lines total, so about 19,370 player rows plus header
- columns align directly with the JPA entity mapping

### `features_db.csv`

- Also 19,371 lines total
- appears derived from `Player.csv`
- includes the original player columns plus engineered model features:
  - `birthFraction`
  - `birthZ`
  - `weightZ`
  - `heightZ`
  - `batsN`
  - `throwsN`

This file is what the Python model actually uses at runtime.

## 7. API Surface Summary

### Java Service

- `GET /v1/players`
  - full player list
- `GET /v1/players/{id}`
  - one player by `PLAYERID`
- `POST /v1/chat`
  - hard-coded Ollama prompt, no request body handling
- `GET /v1/chat/list-models`
  - Ollama model inventory

### Python Service

- `POST /team/generate`
  - similar-player recommendations
- `POST /team/feedback`
  - exclusion feedback
- `POST /llm/generate`
  - placeholder
- `POST /llm/feedback`
  - placeholder

## 8. What Is Wired vs Not Wired

### Wired and Working in Principle

- Spring Boot app booting with H2 seeded from CSV
- player list and player-by-id endpoints
- Ollama list-models endpoint
- Ollama fixed-prompt chat endpoint
- Python nearest-neighbor team generation service
- Python in-memory feedback exclusions

### Present in Repo but Not Integrated

- Python team-generation service with the Java app
- Python placeholder LLM endpoints with any real LLM
- request-body-driven chat prompts in the Java chat endpoint

## 9. Mismatches, Risks, and Design Debt

### Highest-Value Observations

1. The repo looks like one product, but it is really two disconnected services.
2. The Java chat endpoint is a demo endpoint, not a true chat API.
3. The Python model service is more feature-rich than the Java service, but it is not wired into it.
4. Operational startup depends on a relative CSV file path that can break outside the repo root.
5. Tests are minimal: only a `contextLoads()` smoke test exists.

### Specific Code/Config Debt

- `pom.xml` declares `spring-boot-starter-data-jpa` twice.
- `spring-boot-starter-data-rest` is present but not meaningfully used.
- `RestTemplate` bean exists but is unused.
- several `Logger` fields exist without meaningful logging coverage.
- `Player` uses string-typed fields for everything, including dates/numbers.
- `Player` uses generated UUID annotation on an imported external ID.
- no pagination or search on the full player endpoint.
- no resilience or structured error handling around Ollama outages.
- Python feedback storage is volatile and process-local.
- Python LLM routes are placeholders and internally inconsistent.

## 10. How To Read This Repo Efficiently

If you want the shortest path to understanding it, read in this order:

1. `README.md`
   - understand the intended Java + Ollama demo story
2. `src/main/resources/application.yml`
   - understand runtime assumptions
3. `src/main/resources/schema.sql`
   - understand where the Java app data comes from
4. `src/main/java/com/app/playerservicejava/controller/PlayerController.java`
   - see the public player API
5. `src/main/java/com/app/playerservicejava/service/PlayerService.java`
   - see the actual behavior and fake latency
6. `src/main/java/com/app/playerservicejava/controller/chat/ChatController.java`
7. `src/main/java/com/app/playerservicejava/service/chat/ChatClientService.java`
   - confirm the hard-coded Ollama prompt path
8. `player-service-model/a4a_model/server.py`
   - understand the separate model service
9. `player-service-model/a4a_model/train.ipynb`
   - understand how the Python model artifacts were produced

## 11. Mental Model To Keep In Mind

The cleanest mental model for this repo is:

- "Java app" = CSV-backed player directory + Ollama connectivity demo
- "Python app" = experimental player-similarity recommendation service
- "shared data" = baseball player records reused in both places

If you read it as a unified production architecture, the repo feels inconsistent.
If you read it as a demo repo that combines multiple experiments around the same dataset, the structure makes sense.

## 12. If You Were Extending It

The most likely next directions would be:

### Option A: Keep the Java App as the Primary API

- add a Java client for the Python `/team/generate` and `/team/feedback` endpoints
- expose recommendation endpoints from Spring Boot
- move the Python service to a real downstream dependency

### Option B: Keep the Services Separate but Make That Explicit

- document them as separate runnable components
- give each one its own API docs and use cases
- stop implying that the Java app owns the Python-model behavior

### Option C: Collapse the Prototype Surface

- remove placeholder endpoints
- remove unused dependencies/beans
- narrow the repo to the parts that are actually used

## 13. Bottom Line

Today, this repo is best understood as a demo/learning project built around baseball player data.

Its implemented behavior is:

- load players from CSV into H2
- expose simple player retrieval endpoints
- expose a fixed-prompt Ollama demo endpoint
- separately expose a Flask nearest-neighbor recommendation prototype

Its most important non-obvious fact is this:

**the Python model service exists in the repo, but the Java service does not use it.**

## 14. Local Validation Notes

I validated a few operational assumptions while producing this document.

### Maven Wrapper State

`./mvnw test` did not run successfully in this environment because the wrapper failed Maven distribution SHA validation for the configured `3.9.9` download.

Implication:

- the checked-in Maven wrapper configuration currently appears out of sync with the downloaded artifact it expects
- using a locally installed `mvn` binary is a more reliable path until that wrapper configuration is corrected

### Local `mvn test` Result

Using the system Maven binary:

- the project compiled
- Spring Boot started far enough to initialize the H2 datasource and load the app context
- the single `contextLoads()` test still failed in this environment

Observed failure:

- Mockito inline mock maker could not self-attach through Byte Buddy on the local Oracle JDK 17 runtime

Interpretation:

- the codebase is close enough to boot for context initialization to begin successfully
- but the current test setup is fragile with respect to local JVM/Mockito agent-attachment behavior
