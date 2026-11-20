# montyhall-server-lambda

[![License](https://img.shields.io/badge/license-MIT-lightgray.svg)](https://github.com/andrewmacheret/montyhall-server-lambda/blob/master/LICENSE.md)

A Java AWS Lambda that serves an API for a [Monty Hall](https://en.wikipedia.org/wiki/Monty_Hall_problem) game simulator and statistics engine.

Meant to be consumed by [montyhall-client](https://github.com/andrewmacheret/montyhall-client). Play it at [andrewmacheret.com/projects/montyhall](https://andrewmacheret.com/projects/montyhall/).

## API

The API is at `https://montyhall.andrewmacheret.com`.

| Request | Body | Response |
|---|---|---|
| `GET /` | | `{"version": "2.0.0", "apis": ["/games", "/stats"]}` |
| `POST /games` | `{"numberOfDoors": 3}` (optional, 3 to 100) | The new game |
| `GET /games/{id}` | | The game |
| `PUT /games/{id}/doors/{number}` | `{"state": "SELECTED"}` to pick a door, then `{"state": "OPENED"}` to open one | `{"updated": true}` |
| `DELETE /games/{id}` | | `{"deleted": true}` |
| `GET /stats` | | Counts like `{"stayed-win": 3, "switched-lose": 1, "totals-win": 3, ...}` |

A game looks like `{"id": "...", "state": "SECOND_CHANCE", "doors": [{"number": 1, "state": "SELECTED"}, ...]}`. What's behind a door is only shown once it's opened. Finished games also have `prize` and `switched`.

Errors come back as `{"error": "..."}` with status 400 (bad request), 404 (game or door not found) or 409 (wrong game state).

## Development

Needs a JDK 21 or newer. Maven is downloaded by the wrapper.

```bash
# run tests and build target/montyhall-server-lambda.jar
./mvnw package

# run the API on http://localhost:8080 with an in-memory store
./mvnw -q test-compile exec:java -Dexec.mainClass=monty.LocalServer -Dexec.classpathScope=test

# play 1000 games (half stay, half switch) and print the win rates
scripts/play.py --api http://localhost:8080
```

`scripts/play.py` needs only Python 3. Without `--api` it plays against the live API, and those games count in the public stats. Run `scripts/play.py --help` for the other options (number of games, strategy, door count).

## How it runs

- AWS Lambda function `MontyHall` (Java 21, SnapStart) behind an API Gateway HTTP API, with the custom domain `montyhall.andrewmacheret.com`. API Gateway handles CORS.
- API Gateway calls the function's `live` alias. Each deploy publishes a new version and moves the alias.
- Games and stats are stored in the DynamoDB table `MontyHall`. Games expire a day after their last change.

## Deploy

Pushes to `master` run the tests, deploy, and smoke test the live API through GitHub Actions (see `.github/workflows/deploy.yml`). The workflow signs in to AWS with GitHub OIDC using the IAM role in the `AWS_ROLE_ARN` repo variable.

This used to be a Spring Boot app that ran in Docker. See the git history before version 2.0.0 for that version.
