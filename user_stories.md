# Suggested User Stories

These stories are designed for the current state of this repository:

- `2` stories do **not** require Ollama
- `1` story explicitly **does** use Ollama
- all `3` are medium difficulty and fit the existing Spring Boot + H2 + CSV + optional Python sidecar setup

Recommended implementation order:

1. Story 1
2. Story 2
3. Story 3

---

## Story 1: Searchable, Pageable Player Directory

**Type:** Without Ollama

**User story**

As an API consumer, I want to search and page through players so that I can find relevant records without downloading the entire dataset every time.

**Why this fits this repo**

The current `GET /v1/players` endpoint returns the full player list in one response. That works for a demo, but it is the most obvious place to improve usability and API design without changing the data model.

**Suggested scope**

Add a new read endpoint such as `GET /v1/players/search` or extend `GET /v1/players` with optional query params.

Suggested query params:

- `q`
  - matches `playerId`, `firstName`, `lastName`, or `givenName`
- `birthCountry`
- `bats`
- `throws`
- `page`
- `size`
- `sortBy`
- `sortDir`

**Acceptance criteria**

1. The API supports filtering players by at least one free-text field and at least two structured fields.
2. The API supports pagination with `page` and `size`.
3. The API supports sorting by a limited allowlist such as `playerId`, `firstName`, `lastName`, and `debut`.
4. The response includes pagination metadata, not just the list of players.
5. Invalid paging or sorting inputs return `400 Bad Request` with a clear error message.
6. Existing player lookup by ID continues to work unchanged.
7. The implementation does not load the entire table into memory just to page results after the fact.

**Suggested response shape**

```json
{
  "players": [
    {
      "playerId": "aaronha01",
      "firstName": "Hank",
      "lastName": "Aaron"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "sortBy": "lastName",
  "sortDir": "asc"
}
```

**Implementation notes**

- This story likely requires moving beyond the current simple `findAll()` usage.
- A reasonable path is Spring Data paging plus either:
  - derived repository methods for simpler filters, or
  - a custom repository/specification/query approach for combined filtering
- Keep this story read-only. Do not add player creation or update behavior.
- Preserve backward compatibility if you change `GET /v1/players`; otherwise prefer a new endpoint.

**Suggested tests**

- repository or service test for filter combinations
- controller test for paging and sort validation
- controller test proving unknown `sortBy` is rejected

---

## Story 2: Custom Player Rosters

**Type:** Without Ollama

**User story**

As a user, I want to create and manage custom player rosters so that I can save meaningful groups of players inside the Java application.

**Why this fits this repo**

The current Java service is completely read-only. Adding roster management creates a real domain feature on top of the existing player directory without introducing Python dependencies or Ollama requirements.

**Suggested scope**

Add roster endpoints such as:

- `POST /v1/rosters`
- `GET /v1/rosters`
- `GET /v1/rosters/{id}`
- `POST /v1/rosters/{id}/players`
- `DELETE /v1/rosters/{id}/players/{playerId}`

Suggested roster fields:

- `id`
- `name`
- `description`
- `createdAt`
- `players`

**Acceptance criteria**

1. The Java app supports creating a roster with a required `name` and optional `description`.
2. The Java app supports listing all rosters and fetching one roster by ID.
3. The Java app supports adding existing players to a roster by `playerId`.
4. The Java app supports removing a player from a roster.
5. Adding a player to a roster fails with `404 Not Found` if the player does not exist in the `PLAYERS` table.
6. Adding the same player to the same roster twice does not create duplicates.
7. Creating a roster with invalid input such as blank `name` returns `400 Bad Request`.
8. The existing player endpoints continue to work unchanged.

**Suggested response shape**

```json
{
  "id": 1,
  "name": "Left-Handed Pitchers",
  "description": "Roster for scouting left-handed pitchers",
  "createdAt": "2026-04-14T15:00:00Z",
  "players": [
    {
      "playerId": "abbotji01",
      "firstName": "Jim",
      "lastName": "Abbott"
    }
  ]
}
```

**Implementation notes**

- This story requires introducing new persistence beyond the CSV-backed `PLAYERS` table.
- A clean approach is to add new tables for:
  - `ROSTERS`
  - `ROSTER_PLAYERS`
- Since `spring.jpa.hibernate.ddl-auto` is `none`, you will need to manage the schema explicitly.
- Keep the original player data read-only. Rosters should reference players, not copy them.
- Add DTOs for roster requests and responses rather than exposing JPA entities directly.
- Decide whether roster IDs should be numeric or UUID-based and stay consistent.
- If you want a slightly richer version, you can add a maximum roster size such as `25`.

**Suggested tests**

- repository or service test for duplicate player prevention
- controller test for roster creation validation
- controller test for `404` when adding an unknown player ID

---

## Story 3: Player Q&A with Ollama

**Type:** With Ollama

**User story**

As a user, I want to ask a natural-language question about a specific player and receive a grounded answer generated by Ollama so that I can explore the player dataset in a more conversational way.

**Why this fits this repo**

The current Ollama integration is a fixed prompt that always asks for a recursion haiku. It proves connectivity, but it is not yet useful. This story upgrades that integration into a real player-aware feature while staying within the repo’s existing boundaries.

**Suggested scope**

Add an endpoint such as:

- `POST /v1/players/{id}/ask`

Suggested request body:

```json
{
  "question": "Summarize this player's career and physical profile in 3 sentences.",
  "model": "tinyllama"
}
```

**Acceptance criteria**

1. The endpoint accepts a player ID path parameter and a JSON body containing a non-empty `question`.
2. The implementation looks up the player from the H2-backed repository before calling Ollama.
3. The prompt sent to Ollama includes structured player context from the repository so the model answer is grounded in the dataset.
4. If the player does not exist, the endpoint returns `404 Not Found`.
5. If Ollama is unavailable, the endpoint returns a structured `503 Service Unavailable` response with a clear message.
6. The response includes at least:
   - `playerId`
   - `question`
   - `model`
   - `answer`
7. The existing `GET /v1/chat/list-models` endpoint keeps working.
8. The old fixed-prompt `POST /v1/chat` endpoint is either preserved for backward compatibility or intentionally replaced with a documented change.

**Suggested response shape**

```json
{
  "playerId": "aaronha01",
  "question": "Summarize this player's career and physical profile in 3 sentences.",
  "model": "tinyllama",
  "answer": "Hank Aaron was a right-handed hitter born in Mobile, Alabama..."
}
```

**Implementation notes**

- Add request and response DTOs instead of returning a raw string.
- Build the prompt from actual player fields such as:
  - first name
  - last name
  - given name
  - birth location
  - bats
  - throws
  - height
  - weight
  - debut
  - final game
- Keep the prompt narrow and factual so the model is guided to use the provided record.
- Make the Ollama model configurable, but keep `tinyllama` as the default if you want to preserve the current local setup.
- Add validation for empty or overly large questions.

**Suggested tests**

- service test that verifies the prompt includes player data
- controller test for `404` on unknown player ID
- controller or service test for Ollama failure mapping

---

## Optional Stretch Add-Ons

If you want to expand any of these after the first implementation:

- Story 1
  - add response caching for common queries
  - add a `fields` parameter to return lightweight player summaries
- Story 2
  - add a maximum roster size and enforce it consistently
  - add a roster rename and delete endpoint
- Story 3
  - add prompt templates such as `summary`, `scouting-report`, and `fun-facts`
  - add a guardrail that instructs the model not to invent missing data

## Recommended Difficulty Order

If you want a smooth progression:

1. Story 1 teaches better repository and API design in the current Java app.
2. Story 2 adds new persistence, validation, and relational modeling.
3. Story 3 adds the most product-facing AI behavior while reusing the improved API patterns.
