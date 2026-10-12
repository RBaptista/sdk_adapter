# sdk_adapter: Mojaloop DFSP simulator over the SDK scheme adapter

This is the same multi-DFSP simulator as `dfsp_adapter`: Keycloak users, one double-entry ledger per DFSP, and settlement reconciliation. The difference is that it doesn't speak FSPIOP itself. Each hosted DFSP connects to the hub through its own **Mojaloop SDK scheme adapter** (`mojaloop/sdk-scheme-adapter`):

- **Payer:** the app calls the SDK **outbound API** (`:4001`). The calls are synchronous, and each step is a separate call: discovery, then the quote, then the transfer.
- **Payee:** the SDK calls the app's **backend API** at `/sdk/{fspId}` (`GET /parties`, `POST /quoterequests`, `POST /transfers`, `PUT /transfers/{id}`, and `POST /bulkQuotes`, `POST /bulkTransfers` for bulk).
- **G2P:** batches go through the SDK's `/bulkTransactions`, so the hub sees real bulk quotes and bulk transfers. See [G2P disbursements](#g2p-disbursements).
- **The SDK handles:** FSPIOP headers and resource versions (2.0 for parties, quotes and transfers in this setup), the ILP packet, condition and fulfilment, and the async callbacks. The app doesn't touch any of these.

```
 Keycloak user (fsp_id=sdkbank)
        │ /api/** (bearer)
        ▼
 ┌──────────────────────┐  outbound :4101   ┌──────────────┐  FSPIOP   ┌──────────────────────┐
 │ sdk_adapter (:8091)  │ ────────────────▶ │ sdkbank-sdk  │ ────────▶ │ Mojaloop hub         │
 │  payer · payee       │                   └──────────────┘           │ (ml-core-test-harness)│
 │  ledger · reconcile  │  backend /sdk/sdkwallet ┌──────────────┐ ◀── │ ALS · quoting ·       │
 │                      │ ◀────────────────────── │ sdkwallet-sdk│     │ ml-api-adapter · CL   │
 └──────────────────────┘                         └──────────────┘     └──────────────────────┘
        │ central-ledger admin (onboarding, funds-in), ALS DELETE, central-settlement (reconciliation)
        └──────────────────────────────────────────────────────────────▶ hub, directly
```

The app still calls a few hub admin services directly, because the SDK doesn't cover them:
- central-ledger: participant, NDC limit, endpoints, activation and funds-in;
- the ALS: removing a party;
- central-settlement: positions and settlements, for reconciliation.

Party **registration** goes through the SDK (`POST /accounts`).

## Running against ml-core-test-harness

Requirements: Java 21, Docker, the harness running (network `mojaloop-net`), Keycloak on :8080 and PostgreSQL on :5432.

1. **SDKs.** Run `docker compose -f docker/sdk/docker-compose.yml up -d`. This starts `sdkbank-sdk` and `sdkwallet-sdk`, each with its own Redis, on `mojaloop-net`.
   - Their outbound APIs are published on `localhost:4101` and `localhost:4201`.
   - Their `BACKEND_ENDPOINT` is `172.24.0.1:8091/sdk/<fsp>` (override with `SDK_BACKEND_HOST`).
   - The harness's own SDKs (payerfsp, testfsp1…) are left alone; they serve the Testing Toolkit.
   - For bulk (G2P) it also starts each SDK's command and domain event handlers, a one-shot job that creates their Kafka topics, and **`bulk-api-adapter`** (:3003). The harness lacks that one hub service; see [G2P disbursements](#g2p-disbursements).
2. **Firewall.** The SDK containers must reach the app on the host: `sudo ufw allow from 172.24.0.0/16 to any port 8091 proto tcp`.
3. **Database.** Create it with `docker exec postgres_db psql -U postgres -c 'create database sdk_dfsp'`. Put `DB_PASSWORD=...` in `.env`, which is git-ignored.
4. **App.** Start it with `./mvnw quarkus:dev`. It runs on port **8091**, so it can run next to `dfsp_adapter` on 8090. Swagger UI is at <http://localhost:8091/q/swagger-ui>.
5. **Setup.** Run `./scripts/setup-sdk-adapter.sh`. It is safe to re-run. It:
   - imports the Keycloak realm `sdk`;
   - provisions the hub: hub accounts, the `DEFERREDNETCVE` settlement model, and the MSISDN oracle;
   - creates and onboards `sdkbank` and `sdkwallet`, with their 15 endpoints (11 single, 4 bulk) pointing at `http://<fsp>-sdk:4000`;
   - creates the demo customers and registers their MSISDNs (2389930000, 2389940000) in the ALS;
   - checks discovery and sends a 100 CVE test transfer;
   - pays a G2P batch from the government customer (2389950000) to sdkwallet's demo customer.

Keycloak realm `sdk`, public client `sdk-adapter`. Users:
- `sdk-admin`/`admin` (role `admin`)
- `sdkbank-user`/`sdkbank` (`fsp_id=sdkbank`)
- `sdkwallet-user`/`sdkwallet` (`fsp_id=sdkwallet`)

| Env var | Default |
| --- | --- |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/sdk_dfsp` / `postgres` / – |
| `KEYCLOAK_URL` / `KEYCLOAK_REALM` | `http://localhost:8080` / `sdk` |
| `SDK_INBOUND_URL_TEMPLATE` | `http://<fspId>-sdk:4000` (the hub's view of a DFSP's SDK; used when a DFSP is created without `sdkInboundUrl`) |
| `HUB_ADMIN_URL` (central-ledger) | `http://localhost:3001` |
| `HUB_ALS_URL` | `http://localhost:4002` |
| `HUB_SETTLEMENT_URL` (central-settlement) | `http://localhost:3007/v2` |

Other settings (prefix `simulator.`):
- `sdk.request-timeout` (60s). It must be longer than the SDK's `REQUEST_PROCESSING_TIMEOUT_SECONDS` (30s).
- `transfer-expiry` (60s) and `callback-timeout` (30s). The callback timeout is the grace period before a stuck RESERVED transfer is resolved via the SDK or expired.
- `payee.transfer-state` (`COMMITTED`/`RESERVED`), `payee.fee-percent`, and `settlement.reconcile-every`.
- `disbursement.mode` (`BULK`, or `INDIVIDUAL`; env `DISBURSEMENT_MODE`) and the other `disbursement.*` settings: see [G2P disbursements](#g2p-disbursements).

### Adding a DFSP

1. Add an SDK + Redis pair to `docker/sdk/docker-compose.yml`:
   - `DFSP_ID`;
   - its own Redis `CACHE_URL`;
   - `BACKEND_ENDPOINT=…:8091/sdk/<fsp>`;
   - a free published outbound port;
   - for bulk: its `<fsp>-sdk-command-handler` and `<fsp>-sdk-domain-handler`, the `BACKEND_EVENT_*`/`FSPIOP_EVENT_*` settings, and its two topics (`topic-<fsp>-sdk-command-events`, `topic-<fsp>-sdk-domain-events`) in `sdk-kafka-topics`. Copy sdkbank's.
2. Add a Keycloak user with `fsp_id=<fsp>`.
3. Create the DFSP with `sdkOutboundUrl`, then onboard it:

```shell
curl -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' localhost:8091/api/admin/dfsps \
  -d '{"fspId":"newfsp","currency":"CVE","sdkOutboundUrl":"http://localhost:4301","netDebitCap":100000,"initialFunds":100000}'
curl -X POST -H "Authorization: Bearer $ADMIN" localhost:8091/api/admin/dfsps/newfsp/onboard
```

## Walkthrough

```shell
tok() { curl -s -d grant_type=password -d client_id=sdk-adapter -d username=$1 -d password=$2 \
  http://localhost:8080/realms/sdk/protocol/openid-connect/token | jq -r .access_token; }
BANK=$(tok sdkbank-user sdkbank); API=http://localhost:8091/api; J='Content-Type: application/json'

curl -s -H "Authorization: Bearer $BANK" $API/parties/lookup/MSISDN/2389940000          # discovery via the SDK
T=$(curl -s -H "Authorization: Bearer $BANK" -H "$J" $API/transfers -d '{
  "from":{"idType":"MSISDN","idValue":"2389930000"},"to":{"idType":"MSISDN","idValue":"2389940000"},
  "amount":25,"currency":"CVE"}' | jq -r .transferId)                                    # WAITING_FOR_PARTY_ACCEPTANCE
curl -s -X PUT -H "Authorization: Bearer $BANK" -H "$J" $API/transfers/$T/accept-party -d '{"accept":true}'  # quoted
curl -s -X PUT -H "Authorization: Bearer $BANK" -H "$J" $API/transfers/$T/accept-quote -d '{"accept":true}'  # COMMITTED
```

With `"autoAccept":true` the POST runs all three steps and returns the final state.

## Postman

Import these two files into Postman (or Bruno) and select the environment:
- `postman/sdk_adapter.postman_collection.json`
- `postman/sdk_adapter.postman_environment.json`

1. Run a request in **auth** first: `sdkbank-user`, `sdkwallet-user` or `sdk-admin`. It stores the access token in the environment's `token`, and every `/api` request uses it.
2. Create requests save the new id in a collection variable (`customerId`, `accountId`, `partyId`, `transferId`, `disbursementId`), so the get, update and accept requests after them work without editing.
3. `payerMsisdn` and `payeeMsisdn` default to the setup script's demo parties. Change `msisdn` before creating another party, because an identifier can be registered only once.
4. **disbursements (G2P)** pays from `governmentMsisdn` (2389950000), the government customer that step 7 of the setup script creates; log in as `sdkbank-user`. Run create, validate, then get until `VALIDATED`, then approve, then get until the batch is final. The scheduler does the lookups and payments, so allow a few seconds between steps.

The folders that go beyond `/api` don't need a token:
- **sdk backend** calls `/sdk/sdkwallet/...` as the SDK would.
- **sdk outbound (direct)** calls the SDKs on :4101 and :4201 without the app.
- **hub (read-only)** shows central-ledger's participants, positions and endpoints.

The admin folder's create/update/onboard requests use `fspId=sdktest` and `sdkOutboundUrl=http://localhost:4301`. That DFSP only works once its SDK container exists; see *Adding a DFSP*.

## G2P disbursements

A G2P batch is a government (or any payer party of a hosted DFSP) paying many beneficiaries. The user drives it through `/api/disbursements`:

```
POST /api/disbursements                → CREATED      batch and items (PENDING)
PUT  /api/disbursements/{id}/validate  → VALIDATING   payee lookups
GET  /api/disbursements/{id}           → VALIDATED    items VALID (payee name and FSP) or INVALID (e.g. 3204)
PUT  /api/disbursements/{id}/approve   → EXECUTING    refused with 4001 unless the available balance covers the VALID items
GET  /api/disbursements/{id}           → COMPLETED / PARTIALLY_COMPLETED / FAILED
GET  /api/disbursements/{id}/items[?state=…]          PUT /api/disbursements/{id}/cancel
```

COMPLETED means every line was paid. An INVALID or FAILED line makes the batch PARTIALLY_COMPLETED, or FAILED if nothing was paid. Each paid line is an ordinary OUTBOUND transfer (and INBOUND at the payee), so ledger, positions and settlement reconciliation treat it like any other transfer.

### BULK mode (default): the Mojaloop bulk sequence

`simulator.disbursement.mode=BULK` uses the payer SDK's `/bulkTransactions`. This is the sequence Mojaloop defines for G2P:

```
validate   app → payer SDK   POST /bulkTransactions (autoAcceptParty/autoAcceptQuote off)      → 202
           payer SDK → ALS   GET /parties, one per item (FSPIOP has no bulk lookup)
           payer SDK → app   PUT /sdk/{payer}/bulkTransactions/{id}  WAITING_FOR_PARTY_ACCEPTANCE → items VALID/INVALID
approve    app → payer SDK   PUT /bulkTransactions/{id}  acceptParty per item
           payer SDK → quoting-service   POST /bulkQuotes, one per payee DFSP
                      → payee SDK → app  POST /sdk/{payee}/bulkQuotes
           payer SDK → app   PUT /sdk/{payer}/bulkTransactions/{id}  WAITING_FOR_QUOTE_ACCEPTANCE
           app: each quoted item becomes an OUTBOUND transfer with its funds reserved
           app → payer SDK   PUT /bulkTransactions/{id}  acceptQuote per item (false if it couldn't be reserved)
           payer SDK → bulk-api-adapter → central-ledger bulk handlers   POST /bulkTransfers, one per payee DFSP
                      → payee SDK → app  POST /sdk/{payee}/bulkTransfers (each transfer booked and committed)
           payer SDK → app   PUT /sdk/{payer}/bulkTransactions/{id}  COMPLETED, each item's transferState
           app: commits or releases each reservation
```

- **Hub side.** ml-core-test-harness already has what central-ledger needs for bulk: its bulk handlers (they run in the `central-ledger` process), MongoDB (`objstore`) and the bulk Kafka topics. The one missing service, `mojaloop/bulk-api-adapter` (v17.1.9, which ships with central-ledger v19.x), runs from `docker/sdk/docker-compose.yml`. Onboarding registers the 4 bulk endpoints (`FSPIOP_CALLBACK_URL_BULK_QUOTES`, `…_BULK_TRANSFER_POST/PUT/ERROR`).
- **SDK side.** `/bulkTransactions` needs the SDK's backend and FSPIOP event handlers turned on in `api-svc`, plus its `outbound-command-event-handler` and `outbound-domain-event-handler` (same image, `yarn start:command-handler` / `start:event-handler`). They talk over the harness's Kafka, on topics of their own per DFSP. `BULK_QUOTES_ENDPOINT=quoting-service:3002` and `BULK_TRANSFERS_ENDPOINT=bulk-api-adapter:3003`.
- **Payer.** The batch id is the SDK's `bulkTransactionId`; each item's `homeTransactionId` matches the SDK's results to items. Approval accepts the parties. The quotes are then accepted automatically for every item whose funds could be reserved: there is no second approval for fees.
- **Payee.** The SDK's individual quote id is the transfer id, so that is how a bulk transfer finds its quote. Bulk transfers are committed at once, even with `payee.transfer-state=RESERVED`: the SDK relays no final notification for bulk.
- **Recovery.** An item still waiting for the party or quote report after `disbursement.bulk-timeout` (5m) fails with 2004. A RESERVED item whose outcome never arrives is resolved by the expiry job (`GET /transfers/{id}`), as for single transfers.
- **Limits.** A BULK batch is validated once: the SDK holds the bulk transaction from then on. The SDK keeps it for `disbursement.bulk-expiry` (1h).

### INDIVIDUAL mode

`simulator.disbursement.mode=INDIVIDUAL` (`DISBURSEMENT_MODE=INDIVIDUAL`) needs none of the bulk services. The scheduler (`disbursement.every`, 2s) looks each payee up, then pays each item as its own auto-accepted P2P transfer, `disbursement.parallelism` (5) at a time. Each item's `homeTransactionId` makes the payment idempotent. An item claimed but never sent is queued again after `disbursement.resubmit-after` (5m). The hub sees ordinary quotes and transfers, not bulk ones.

The mode is stored on each batch when it is created, so changing it only affects new batches.

## Monitoring

`./scripts/monitoring.sh` runs the harness's `docker-compose-monitoring.yml` with the override in `docker/monitoring/`, which leaves the harness checkout as it is (set `HARNESS_DIR` if it isn't `~/Documents/ml-core-test-harness`).

The override fixes three things:
- **Prometheus targets.** The harness's own config targets its performance profile (`ml-core-*-N` replicas, perf simulators). The override scrapes this hub's containers instead: ALS on 4001 and 4002, every SDK on `:4004`, plus the Kafka and MySQL exporters.
- **Database name.** Grafana and `mysqlexporter-cl` point at `mysql` rather than `mysql-cl`.
- **Exporter user.** The script creates the `exporter` user that `mysqlexporter-cl` needs in the central-ledger database.

The override also adds a **Mojaloop - Settlements** dashboard (`docker/monitoring/dashboards/`). central-settlement exposes no Prometheus metrics, so the dashboard reads the central-ledger database through Grafana's MySQL datasource. It shows:
- the open window: its age and transfer count;
- windows and settlements, with their states and durations;
- per-DFSP net amounts, and positions over time annotated with window closes and settlements;
- liquidity against the NDC;
- every state transition.

Two Kafka panels (from kafka-exporter) show the window-close events and the consumer lag of central-settlement.

The harness dashboards are in Grafana at http://localhost:9999 (admin / password). `./scripts/monitoring.sh targets` shows the scrape status. The monitoring stack shares the compose project `ml-core-test-harness` with the hub, so never run it with `--remove-orphans`. sdk_adapter itself exposes no metrics yet.

## Traffic generator

`./scripts/traffic-generator.sh` runs continuous P2P and G2P traffic, for example to watch the hub in Prometheus/Grafana. It uses the demo data from `setup-sdk-adapter.sh`. The money moves in a closed loop, so it can run forever without top-ups or settlement:
- **P2P.** Maria (sdkbank) pays Joao (sdkwallet) random amounts until her balance is drained. Then Joao pays her back until his is drained, and so on.
- **G2P.** Every `G2P_EVERY` seconds (60), the government pays a batch to Joao and to four beneficiaries `2389960001..4`. The script creates those beneficiaries on sdkwallet the first time it runs.
- **REPAY.** While the government is below `GOV_TARGET` (100000), `REPAY_PCT` (30%) of the P2P ticks are a beneficiary paying the government back.
- **SETTLE.** Every `SETTLE_EVERY` seconds (300), the script closes the open settlement window through central-settlement (`:3007`) and settles every closed window that still has unsettled content. Each settlement goes through PS_TRANSFERS_RECORDED, RESERVED and COMMITTED to SETTLED, `SETTLE_STEP_DELAY` (5s) apart. sdk_adapter then reconciles it (`POST /api/settlements/reconcile`), and the script logs MATCH or MISMATCH per DFSP. `ENABLE_SETTLEMENT=false` turns this off.

```shell
./scripts/traffic-generator.sh                                    # Ctrl-C prints a summary
ENABLE_G2P=false MIN_DELAY=0 MAX_DELAY=1 ./scripts/traffic-generator.sh
G2P_EVERY=20 FAIL_PCT=10 ./scripts/traffic-generator.sh          # 10% to unknown MSISDNs: 3204 errors, partial batches
```

The script header lists all the settings. If BULK batches stay in VALIDATING, the SDK's bulk handlers have lost Kafka, for example after the hub was restarted. To recover:
1. `docker compose -f docker/sdk/docker-compose.yml up -d --force-recreate sdk-kafka-topics`
2. `docker restart` the four `*-sdk-command-handler` / `*-sdk-domain-handler` containers and both `*-sdk` containers.

## Ledger model

The ledger model is the same as dfsp_adapter's: CUSTOMER, POSITION and SETTLEMENT accounts.
- **Payer:** reserves `CUSTOMER −x / POSITION +x` before it accepts the quote. The reservation is committed when the SDK reports `COMMITTED`, and released on an error or rejection.
- **Payee:** books `POSITION −x / CUSTOMER +(x − fee) / SETTLEMENT +fee` when its SDK posts the transfer.

If the SDK call that sends the transfer times out, the transfer stays RESERVED. The expiry job asks the SDK (`GET /transfers/{id}`) for the outcome before expiring it.

## Tests

```shell
./mvnw test        # needs Docker (Dev Services PostgreSQL)
```

The tests use two in-process fakes:
- `testhub/FakeSdkResource` emulates the SDK outbound API, and the hub behind it, for every test DFSP. It routes quotes and transfers to the app's own `/sdk/{payee}` backend, so the full payer→payee flow runs in-process.
- `testhub/FakeHubResource` stubs central-ledger admin, ALS delete and central-settlement.

`FakeSdkResource` also plays `/bulkTransactions`: it answers 202, then reports the parties, the bulk quotes and the bulk transfers to the payer's `/sdk/{fsp}/bulkTransactions/{id}`, calling the payee's `/bulkQuotes` and `/bulkTransfers` in between. `BulkDisbursementFlowTest` runs with `mode=BULK`, and `DisbursementFlowTest` with `INDIVIDUAL`.

## Not covered yet

- JWS signing and validation, and mTLS between the SDKs and the hub. They're off, as in the harness.
- `/sdk/**` is unauthenticated and relies on network-level protection.
- The payee doesn't hear from its SDK about a hub error after it answered `COMMITTED`. Reconciliation would show the mismatch.
- Request-to-pay and FX: the SDK supports them, but this app doesn't use them.
- Bulk is used for G2P disbursements only, with both acceptances driven by the app. Payer-side fee review (a second approval after the bulk quotes) and a payee answering a bulk transfer with RESERVED aren't supported.
