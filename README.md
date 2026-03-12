# delta-backend

Backenden som hører til [delta-frontend](https://github.com/navikt/delta-frontend).

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

## Hemmeligheter (Secrets)

Følgende hemmeligheter må injiseres som miljøvariabler i NAIS:

| Miljøvariabel | Beskrivelse |
|---|---|
| `AZURE_APP_CLIENT_ID` | Azure AD app client ID (injiseres automatisk av NAIS) |
| `AZURE_APP_CLIENT_SECRET` | Azure AD app client secret (injiseres automatisk av NAIS) |
| `AZURE_APP_TENANT_ID` | Azure AD tenant ID (injiseres automatisk av NAIS) |
| `DELTA_EMAIL_ADDRESS` | E-postadressen til Delta-postboksen (f.eks. `ikkesvar.delta@nav.no`) |
| `WEBHOOK_BASE_URL` | Offentlig URL til nginx-relayet (f.eks. `https://delta-webhook.nav.no`) |
| `WEBHOOK_CLIENT_STATE` | Hemmelig streng for å validere at webhook-varsler kommer fra MS Graph. Generer med: `openssl rand -hex 32` |

`WEBHOOK_CLIENT_STATE` lagres inn i Nais console secret `delta-backend-webhook-secret` og refereres i `nais.yaml`.

## Azure AD-tilganger

App-registreringen trenger følgende **application permissions** (ikke delegated) i Microsoft Graph:

| Permission | Begrunnelse |
|---|---|
| `Calendars.Read` | Lese kalenderhendelser og opprette/fornye/slette webhook-subscriptions på postboksen |

