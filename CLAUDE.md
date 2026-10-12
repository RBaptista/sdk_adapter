# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Quarkus 3 (Java 21, Maven) **Mojaloop DFSP simulator** that reaches the hub through the **Mojaloop SDK scheme adapter**. It runs one SDK container per hosted DFSP, defined in `docker/sdk/docker-compose.yml`.

It is a port of `../dfsp_adapter`, with the same hexagonal structure, ledger, Keycloak `/api` and reconciliation. The difference is that the FSPIOP transport (`/fspiop` callbacks, `FspiopHubClient`, `IlpService`, `CallbackRegistry`) is replaced by two SDK adapters:
- the payer side calls the SDK outbound API;
- the SDK calls the app's backend API at `/sdk/{fspId}` for the payee side.

README.md covers setup against ml-core-test-harness.

## Commands

```shell
docker compose -f docker/sdk/docker-compose.yml up -d   # sdkbank-sdk (:4101), sdkwallet-sdk (:4201), their bulk handlers, bulk-api-adapter (:3003)
./mvnw quarkus:dev                                       # port 8091; reads DB_PASSWORD from .env
./scripts/setup-sdk-adapter.sh                           # realm, hub provisioning, onboarding, demo data, checks
./scripts/monitoring.sh                                  # harness Prometheus/Grafana (:9090/:9999) + docker/monitoring override
./scripts/traffic-generator.sh                           # endless closed-loop P2P + G2P traffic
./mvnw test                                              # needs Docker (Dev Services PostgreSQL)
./mvnw test -Dtest=P2pFlowTest
```

## Architecture (hexagonal, everything under `cv.sitech`)

- `domain/`, the persistence adapters, `LedgerService`, `AccountService`, `CustomerService` and `SettlementService` are as in dfsp_adapter. dfsp_adapter's CLAUDE.md conventions still apply:
  - MapStruct names must match;
  - each `XPersistenceAdapter` needs both a no-arg and an `@Inject` constructor;
  - balances are read inside transactions;
  - every entity change needs a new Flyway migration.
- `application/port/out/SdkOutboundGateway` → `adapter/out/sdk/SdkOutboundClient`:
  - The base URL is `dfsp.sdk_outbound_url`.
  - Calls are synchronous. `startTransfer` stops at WAITING_FOR_PARTY_ACCEPTANCE, `acceptParty` returns the quote, and `acceptQuote` returns the fulfil.
  - When the SDK reports an FSPIOP error, the call returns it in `SdkTransfer.error`; that outcome is final.
  - When there's no FSPIOP error (unreachable, 504, or a 5xx without `mojaloopError`), it throws `SdkCallException`, because the outcome at the hub is unknown.
- `adapter/in/sdk/SdkBackendResource`:
  - It is synchronous, because the SDK waits for the answer.
  - Errors must use the body `{statusCode, message}` with a valid FSPIOP code. The SDK ignores the HTTP status and turns the body into the FSPIOP error callback.
  - An empty 200 body makes the SDK send *no* callback.
- `adapter/out/hub`: `CentralLedgerAdminClient` and `CentralSettlementClient` handle onboarding, endpoints, funds-in, ALS DELETE and reconciliation, which the SDK doesn't do.
  - `onboard` registers 15 endpoints (11 single, 4 bulk) with base `dfsp.sdk_inbound_url`, e.g. `http://sdkbank-sdk:4000`.
  - `recordFundsIn` skips the call when the hub settlement account is already non-zero, so re-onboarding is safe.

### Flows

- **Payer (`PayerService`).** The row is created with `transferId = homeTransactionId = UUID`. After discovery, `transferId` is replaced by the SDK's id; that id is also the hub's transfer id and the quote's transactionId. The ledger reference is `OUT:{homeTransactionId}`, because it never changes.
  - **Store state before each SDK call.** The states go WAITING_FOR_PARTY_INFO, WAITING_FOR_QUOTE, then RESERVED (the funds reservation commits before `acceptQuote`).
  - If `acceptQuote` throws, the transfer stays RESERVED. `expire()` asks `GET /transfers/{id}` before expiring it.
  - A rejection, or a failed reservation, also sends `accept…:false` so the SDK drops its state.
- **Payee (`PayeeService`).**
  - The quote is stored as an INBOUND QUOTED row keyed by `quoteId`. The SDK's `POST /transfers` carries our earlier quote response in `quote`, and that is how the transfer is matched to its quote.
  - In COMMITTED mode the payee reserves and commits in one transaction. In RESERVED mode it answers RESERVED, then commits on `PUT /transfers/{id}` (the SDK relays the hub's PATCH; read `finalNotification.transferState`, or `currentState` as a fallback).
  - The SDK builds the ILP packet, condition and fulfilment with its own `ILP_SECRET`. The app never sees them on the payee side.
- **G2P (`DisbursementService`, `/api/disbursements`).** Batch: CREATED → `validate` → VALIDATING → VALIDATED → `approve` → EXECUTING → COMPLETED / PARTIALLY_COMPLETED / FAILED, or CANCELLED. `approve` checks that the available balance covers the VALID items; each item still reserves its own funds. A batch's `mode` (`simulator.disbursement.mode`, stored per batch, V6) picks the path:
  - **BULK (`BulkDisbursementService`, default).** The SDK's `/bulkTransactions`, with `bulkTransactionId` = `disbursementId` and items matched by `homeTransactionId`. `validate` POSTs it; the SDK's report `PUT /sdk/{fsp}/bulkTransactions/{id}` (WAITING_FOR_PARTY_ACCEPTANCE) makes items VALID/INVALID and stores the SDK's `transferId`. `approve` moves VALID items to QUOTING and sends `acceptParty`. On WAITING_FOR_QUOTE_ACCEPTANCE each QUOTING item becomes an OUTBOUND RESERVED transfer (`PayerService.reserveQuoted`, item SUBMITTED in the same transaction), and `acceptQuote` goes back on a virtual thread, never inside the SDK's callback request. The final report settles each RESERVED transfer (`PayerService.settle`); `sync()` then updates the items. `timeouts()` fails items still PENDING/QUOTING after `bulk-timeout` with 2004. A BULK batch is validated only once.
  - **Payee side of bulk.** `POST /sdk/{fsp}/bulkQuotes` prices each individual quote with `PayeeUseCase.quote`; `POST /sdk/{fsp}/bulkTransfers` books each transfer with `prepareBulkTransfer`, which always commits (the SDK relays no final notification for bulk). The individual quote id is the transfer id. Errors are per item (`errorResponse: {statusCode, message}`).
  - **INDIVIDUAL.** Each item is paid as its own auto-accepted `PayerService.start`. `DisbursementJobs` (`simulator.disbursement.every`) does the work: party lookups for PENDING items, then workers claim QUEUED items one at a time with `FOR UPDATE SKIP LOCKED`. `start` is idempotent on the item's `homeTransactionId`; a SUBMITTED item with no transfer row after `resubmit-after` was never sent and is queued again (INDIVIDUAL only).
  - Both modes: `sync()` resolves SUBMITTED items from their transfers (a RESERVED one waits for the expiry job) and closes batches. Never hold a DB transaction across an SDK call; item updates go through `findByIdForUpdate` and only apply if the item is still in the expected state.
- **Bulk infrastructure.** ml-core-test-harness has central-ledger's bulk handlers, `objstore` (MongoDB) and the bulk Kafka topics, but not `bulk-api-adapter`, which `docker/sdk/docker-compose.yml` adds (v17.1.9, config via `BKAPI_*` env, event-SDK config from `docker/sdk/event-sdk.json`). Each SDK runs `api-svc` with `ENABLE_BACKEND_EVENT_HANDLER`/`ENABLE_FSPIOP_EVENT_HANDLER=true`, plus its command and domain event handlers, all on Kafka topics of its own (`topic-<fsp>-sdk-command-events`, `topic-<fsp>-sdk-domain-events`, created by `sdk-kafka-topics`).
- **SDK config that the code depends on.** Each SDK must run with:
  - `AUTO_ACCEPT_PARTY=false` and `AUTO_ACCEPT_QUOTES=false`; the app drives both steps;
  - `BACKEND_ENDPOINT` as `host:port/sdk/<fspId>`, without `http://`, which the SDK prepends;
  - its own Redis;
  - `BULK_QUOTES_ENDPOINT=quoting-service:3002` and `BULK_TRANSFERS_ENDPOINT=bulk-api-adapter:3003`, else bulk requests go to ml-api-adapter.
- **Networking.** The SDKs reach the app at the `mojaloop-net` gateway (`172.24.0.1:8091`), so the host firewall (ufw) must allow it. Dev mode binds to `0.0.0.0`.

### Gotchas

- SmallRye config mangles `{…}` inside `${VAR:default}`, so the inbound URL template uses `<fspId>`, not `{fspId}`.
- Keycloak's user profile rejects parentheses in first and last names ("Account is not fully set up" on login).
- The ALS admin API (`:4001`) requires `Content-Type` and `Date` headers, even on GET.

## Tests

- `testhub/FakeSdkResource` serves `/test-sdk/{fspId}` (the SDK outbound API) and plays the hub. It is an ALS oracle fed by `POST /accounts`, and it routes quotes and transfers to the app's own `/sdk/{payee}`. In RESERVED mode it sends the PATCH-style `PUT /transfers/{id}`.
- `FakeSdkResource.TIMEOUT_AFTER_COMMIT`: payees whose transfer commits but whose acceptQuote answers 504 (unknown outcome).
- `FakeSdkResource` plays `/bulkTransactions` asynchronously (202, then reports to the payer's backend on a virtual thread), grouping bulk quotes and bulk transfers by payee DFSP. `BulkDisbursementFlowTest` runs under a profile with `mode=BULK` and its own DFSPs (`g2pbulkgov`, `g2pbulkwallet`); `%test` defaults to `INDIVIDUAL`.
- `DisbursementFlowTest` uses its own DFSPs (`g2pgov`, `g2pwallet`) so its payments stay out of `HubIntegrationTest`'s settlement window.
- `testhub/FakeHubResource` stubs central-ledger admin, ALS delete and settlement, and keeps the hub POSITIONS that FakeSdk moves.
- `Fixtures.dfsp` creates DFSPs with `sdkOutboundUrl = FakeSdkResource.outboundUrl(fspId)`.
- Auth, `QuarkusTestProfile` and `@TestTransaction` caveats are the same as in dfsp_adapter.
