# delta-backend

Backenden som hører til [delta-frontend](https://github.com/navikt/delta-frontend)

## Avhengigheter

- Java 21
- Docker (På Mac anbefales [Colima](https://github.com/abiosoft/colima))
- docker-compose (Mac: `brew install docker-compose`, Ubuntu: `apt install docker-compose`)

## Hvordan kjøre backenden

- Forsikre deg om at Postgres-databasen kjører
  - `docker-compose up -d`
- Start opp backenden
  - `./gradlew run`

Hvis du ser `INFO  ktor.application - Responding at http://0.0.0.0:8080` i loggen er backenden oppe og kjøre :)
