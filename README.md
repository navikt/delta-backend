# delta-backend

Backenden som hører til [delta-frontend](https://github.com/navikt/delta-frontend).

## Avhengigheter

- Java 25
- Docker (På Mac anbefales [Colima](https://github.com/abiosoft/colima))
- docker-compose (Mac: `brew install docker-compose`, Ubuntu: `apt install docker-compose`)

Versjoner for Gradle-avhengigheter og plugins ligger i `gradle/libs.versions.toml`.

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
| `DELTA_MAINTAINERS_GROUP_ID` | Entra ID-gruppe for Delta-forvaltere: faggruppe-admin og tilgang til funksjoner satt til `maintainers`. Gruppen må også stå under `azure.application.claims.groups` i `nais.yaml` |
| `FEATURE_ROOM_BOOKING` | `off` (default), `maintainers` eller `all`. Slår på rombooking. Se `docs/teams-meeting-room-booking-plan.md` |
| `FEATURE_TEAMS_MEETING` | `off` (default), `maintainers` eller `all`. Slår på Teams-møter på hendelser |
| `FEATURE_SHARED_CALENDAR` | `off` (default), `maintainers` eller `all`. Nye hendelser får en felles kalenderinvitasjon. Eksisterende hendelser beholder kalendermodellen sin. Se `docs/adr/0001-shared-calendar-event.md` |

`WEBHOOK_CLIENT_STATE` lagres inn i Nais console secret `delta-backend-webhook-secret` og refereres i `nais.yaml`.

## Azure AD-tilganger

App-registreringen trenger følgende **application permissions** (ikke delegated) i Microsoft Graph:

| Permission | Begrunnelse |
|---|---|
| `Calendars.Read` | Lese kalenderhendelser og opprette/fornye/slette webhook-subscriptions på postboksen |
| `Calendars.ReadWrite` | Opprette/oppdatere/slette kalenderhendelser i Delta-postboksen (brukes i dag selv om README tidligere kun nevnte `Calendars.Read`) |
| `Mail.Send` | Sende e-postvarsler fra Delta-postboksen |
| `User.Read.All` | App-only personsøk (`GET /users`) for invitasjonsvelgeren; Graph krever denne application permission for brukerlisten. Krever admin consent før personsøk tas i bruk |
| `GroupMember.Read.All` | Klassifisere videresendte deltakere mot gruppers e-postadresse og `proxyAddresses`; allerede gitt admin consent |
| `Place.Read.All` | Rombooking (feature-tolget bak `FEATURE_ROOM_BOOKING`) — liste rom/romlister og sjekke tilgjengelighet |

## Felles kalender og invitasjoner

Nye hendelser med `FEATURE_SHARED_CALENDAR` aktivert lagres før Microsoft Graph
svarer. API-et viser om kalendersynkroniseringen venter, er ferdig eller har feilet.
V1 støtter individuelle invitasjoner og personsøk, ikke gruppeinvitasjoner.
Lokalt brukes en kalenderadapter som ikke sender ekte invitasjoner.

Frontend-kontrakten er beskrevet i
[`docs/frontend-shared-calendar-handoff.md`](docs/frontend-shared-calendar-handoff.md).
Drift og kontroll mot en ekte postboks er beskrevet i
[`docs/shared-calendar-operations.md`](docs/shared-calendar-operations.md).
