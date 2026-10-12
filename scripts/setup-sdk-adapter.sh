#!/usr/bin/env bash
#
# Sets up sdk_adapter against a Mojaloop hub (ml-core-test-harness) through one SDK scheme adapter
# per hosted DFSP (docker/sdk/docker-compose.yml):
#
#   1. checks that Keycloak, the app, central-ledger and each DFSP's SDK are reachable
#   2. imports the Keycloak realm if it's missing
#   3. provisions the hub for the currency (hub accounts, settlement model, MSISDN oracle)
#   4. creates each DFSP in the app and onboards it in the hub through the app
#      (participant, NET_DEBIT_CAP, 15 callback endpoints incl. bulk -> the DFSP's SDK, activation, funds-in once)
#   5. creates one demo customer per DFSP (account, deposit, MSISDN registered in the ALS via the SDK)
#   6. checks discovery between the DFSPs and, optionally, sends a test transfer
#   7. optionally pays a G2P disbursement from a government account of the first DFSP
#      (BULK mode: SDK /bulkTransactions, hub bulk quotes and bulk transfers via bulk-api-adapter)
#
# Running it again is safe: resources that already exist are skipped.
#
# Usage:   docker compose -f docker/sdk/docker-compose.yml up -d
#          ./mvnw quarkus:dev            # in another terminal
#          ./scripts/setup-sdk-adapter.sh
#
# Needs: bash, curl, jq, uuidgen.

set -uo pipefail

# --- configuration ---------------------------------------------------------------------------

APP_URL="${APP_URL:-http://localhost:8091}"

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8080}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-sdk}"
KEYCLOAK_ADMIN_USER="${KEYCLOAK_ADMIN_USER:-admin}"
KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
CLIENT_ID="${CLIENT_ID:-sdk-adapter}"
APP_ADMIN_USER="${APP_ADMIN_USER:-sdk-admin}"
APP_ADMIN_PASSWORD="${APP_ADMIN_PASSWORD:-admin}"

HUB_ADMIN_URL="${HUB_ADMIN_URL:-http://localhost:3001}"          # central-ledger admin API
HUB_ALS_ADMIN_URL="${HUB_ALS_ADMIN_URL:-http://localhost:4001}"  # account-lookup-service admin API
HUB_NAME="${HUB_NAME:-Hub}"                                      # central-ledger hub participant

# set to "false" when the hub is already provisioned (e.g. by the test harness setup collections)
PROVISION_HUB="${PROVISION_HUB:-true}"
# MSISDN oracle endpoint as seen by the ALS; empty skips oracle registration
ORACLE_URL="${ORACLE_URL-http://als-msisdn-oracle-svc:3000}"

CURRENCY="${CURRENCY:-CVE}"
NET_DEBIT_CAP="${NET_DEBIT_CAP:-1000000}"
INITIAL_FUNDS="${INITIAL_FUNDS:-1000000}"

# DFSPs to host, as "fspId|keycloakUser|password|customerName|msisdn|deposit|sdkOutboundUrl" entries
# separated by ';'. The first one pays the second in the checks. Each Keycloak user needs fsp_id = fspId,
# and each fspId its SDK container (<fspId>-sdk) on the hub network.
DFSP_SPECS="${DFSPS:-sdkbank|sdkbank-user|sdkbank|Maria Silva|2389930000|5000|http://localhost:4101;sdkwallet|sdkwallet-user|sdkwallet|Joao Lopes|2389940000|0|http://localhost:4201}"
IFS=';' read -r -a DFSPS <<<"$DFSP_SPECS"

TEST_TRANSFER="${TEST_TRANSFER:-true}"
TEST_AMOUNT="${TEST_AMOUNT:-100}"

# G2P demo: a government customer of the first DFSP pays "msisdn:amount,..." (default: the second
# DFSP's demo customer). No parentheses in the name: Keycloak's user profile rejects them.
TEST_DISBURSEMENT="${TEST_DISBURSEMENT:-true}"
G2P_NAME="${G2P_NAME:-Ministerio das Financas}"
G2P_MSISDN="${G2P_MSISDN:-2389950000}"
G2P_DEPOSIT="${G2P_DEPOSIT:-100000}"
G2P_BENEFICIARIES="${G2P_BENEFICIARIES:-}"

# --- helpers ---------------------------------------------------------------------------------

BOLD=$'\e[1m'; GREEN=$'\e[32m'; YELLOW=$'\e[33m'; RED=$'\e[31m'; RESET=$'\e[0m'
WARNINGS=0

step() { printf '\n%s== %s%s\n' "$BOLD" "$*" "$RESET"; }
ok()   { printf '  %s✓%s %s\n' "$GREEN" "$RESET" "$*"; }
skip() { printf '  %s•%s %s\n' "$YELLOW" "$RESET" "$*"; }
warn() { printf '  %s!%s %s\n' "$YELLOW" "$RESET" "$*"; WARNINGS=$((WARNINGS + 1)); }
die()  { printf '  %s✗ %s%s\n' "$RED" "$*" "$RESET" >&2; exit 1; }

RESPONSE_FILE=$(mktemp)
trap 'rm -f "$RESPONSE_FILE"' EXIT

# http METHOD URL [BODY] [TOKEN] -> sets STATUS and BODY
http() {
  local method=$1 url=$2 data=${3:-} token=${4:-}
  local args=(-s -o "$RESPONSE_FILE" -w '%{http_code}' -X "$method" "$url" --max-time 120
    -H 'Content-Type: application/json' -H 'Accept: application/json'
    -H "Date: $(LC_ALL=C TZ=GMT date '+%a, %d %b %Y %H:%M:%S GMT')")
  [[ -n $token ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n $data ]] && args+=(-d "$data")
  STATUS=$(curl "${args[@]}" 2>/dev/null) || STATUS=000
  BODY=$(cat "$RESPONSE_FILE" 2>/dev/null || true)
  : >"$RESPONSE_FILE"
}

is_2xx() { [[ $STATUS == 2* ]]; }

already_exists() {
  { [[ $STATUS == 400 || $STATUS == 409 ]] && grep -qiE 'already|exist|duplicate' <<<"$BODY"; } ||
    { [[ $STATUS == 500 ]] && grep -qi 'already set' <<<"$BODY"; }
}

hub_call() {
  local label=$1; shift
  http "$@"
  if is_2xx; then ok "$label"
  elif already_exists; then skip "$label (already exists)"
  else warn "$label failed: HTTP $STATUS $BODY"; return 1
  fi
}

token() {
  curl -s "$KEYCLOAK_URL/realms/$KEYCLOAK_REALM/protocol/openid-connect/token" \
    -d grant_type=password -d client_id="$CLIENT_ID" -d username="$1" -d password="$2" | jq -r '.access_token // empty'
}

spec() { IFS='|' read -r FSP USER PASS NAME MSISDN DEPOSIT OUTBOUND <<<"$1"; }

# demo_customer FSP TOKEN NAME MSISDN DEPOSIT: customer, account, deposit and MSISDN registered in the ALS;
# an existing MSISDN is only registered again
demo_customer() {
  local fsp=$1 t=$2 name=$3 msisdn=$4 deposit=$5 party customer account
  http GET "$APP_URL/api/parties" "" "$t"
  party=$(jq -r --arg m "$msisdn" 'first(.[] | select(.idValue == $m) | .id) // empty' <<<"$BODY" 2>/dev/null)
  if [[ -n $party ]]; then
    http PUT "$APP_URL/api/parties/$party/registration" "" "$t"
    if is_2xx; then skip "$fsp: MSISDN $msisdn already exists, re-registered in the ALS"
    else warn "$fsp: re-registering MSISDN $msisdn failed: HTTP $STATUS $BODY"
    fi
    return 0
  fi
  http POST "$APP_URL/api/customers" "$(jq -n --arg n "$name" --arg m "$msisdn" '{name: $n, contact: {phone: $m}}')" "$t"
  is_2xx || die "$fsp: creating customer failed: HTTP $STATUS $BODY"
  customer=$(jq .id <<<"$BODY")
  http POST "$APP_URL/api/accounts" "{\"customerId\":$customer}" "$t"
  is_2xx || die "$fsp: opening account failed: HTTP $STATUS $BODY"
  account=$(jq .id <<<"$BODY")
  if [[ $deposit != 0 ]]; then
    http POST "$APP_URL/api/accounts/$account/deposits" "{\"amount\":$deposit}" "$t"
    is_2xx || die "$fsp: deposit failed: HTTP $STATUS $BODY"
  fi
  http POST "$APP_URL/api/parties" "{\"idType\":\"MSISDN\",\"idValue\":\"$msisdn\",\"accountId\":$account}" "$t"
  if is_2xx; then ok "$fsp: $name, account $account, deposit $deposit, MSISDN $msisdn registered in the ALS"
  else warn "$fsp: registering MSISDN $msisdn failed: HTTP $STATUS $BODY"
  fi
}

# await_disbursement ID TOKEN TIMEOUT STATE...: polls until the batch is in one of STATE; sets BODY
await_disbursement() {
  local id=$1 t=$2 timeout=$3; shift 3
  local deadline=$((SECONDS + timeout)) state
  while ((SECONDS < deadline)); do
    http GET "$APP_URL/api/disbursements/$id" "" "$t"
    state=$(jq -r .state <<<"$BODY" 2>/dev/null)
    for wanted in "$@"; do [[ $state == "$wanted" ]] && return 0; done
    sleep 2
  done
  return 1
}

for tool in curl jq uuidgen; do command -v "$tool" >/dev/null || die "$tool is required"; done
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REALM_FILE="$SCRIPT_DIR/../src/main/resources/keycloak/sdk-realm.json"

# --- 1. reachability -------------------------------------------------------------------------

step "1. Checking services"
http GET "$KEYCLOAK_URL/realms/master"; [[ $STATUS == 200 ]] && ok "Keycloak at $KEYCLOAK_URL" || die "Keycloak not reachable at $KEYCLOAK_URL"
http GET "$APP_URL/q/openapi";          [[ $STATUS == 200 ]] && ok "sdk_adapter at $APP_URL" || die "sdk_adapter not reachable at $APP_URL (start it with ./mvnw quarkus:dev)"
http GET "$HUB_ADMIN_URL/health";       is_2xx && ok "central-ledger at $HUB_ADMIN_URL" || die "central-ledger not reachable at $HUB_ADMIN_URL (HTTP $STATUS)"
for s in "${DFSPS[@]}"; do
  spec "$s"
  http GET "$OUTBOUND/"
  is_2xx && ok "$FSP SDK outbound API at $OUTBOUND" ||
    die "$FSP SDK not reachable at $OUTBOUND (docker compose -f docker/sdk/docker-compose.yml up -d)"
  # the SDK must reach the app's backend API, e.g. through the host firewall
  BACKEND=$(docker exec "$FSP-sdk" printenv BACKEND_ENDPOINT 2>/dev/null)
  if [[ -n $BACKEND ]]; then
    out=$(docker exec "$FSP-sdk" sh -c "wget -q -O- -T 5 http://$BACKEND/parties/MSISDN/0 2>&1")
    if grep -qE 'timed out|refused|unreachable' <<<"$out"; then
      die "$FSP-sdk cannot reach the app at http://$BACKEND ($out). Allow the port through the host firewall, e.g. sudo ufw allow from 172.24.0.0/16 to any port 8091 proto tcp"
    fi
    ok "$FSP-sdk reaches the app backend at http://$BACKEND"
  fi
done

# --- 2. Keycloak realm -----------------------------------------------------------------------

step "2. Keycloak realm '$KEYCLOAK_REALM'"
http GET "$KEYCLOAK_URL/realms/$KEYCLOAK_REALM"
if [[ $STATUS == 200 ]]; then
  skip "realm already exists"
else
  KC_ADMIN=$(curl -s "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" -d grant_type=password \
    -d client_id=admin-cli -d username="$KEYCLOAK_ADMIN_USER" -d password="$KEYCLOAK_ADMIN_PASSWORD" | jq -r '.access_token // empty')
  [[ -n $KC_ADMIN ]] || die "cannot log in to Keycloak as $KEYCLOAK_ADMIN_USER (set KEYCLOAK_ADMIN_USER/KEYCLOAK_ADMIN_PASSWORD)"
  [[ -f $REALM_FILE ]] || die "realm file not found: $REALM_FILE"
  http POST "$KEYCLOAK_URL/admin/realms" "$(jq --arg r "$KEYCLOAK_REALM" '.realm = $r' "$REALM_FILE")" "$KC_ADMIN"
  is_2xx && ok "realm imported from $REALM_FILE" || die "realm import failed: HTTP $STATUS $BODY"
fi
ADMIN=$(token "$APP_ADMIN_USER" "$APP_ADMIN_PASSWORD")
[[ -n $ADMIN ]] && ok "token for $APP_ADMIN_USER" || die "cannot get a token for $APP_ADMIN_USER"

# --- 3. hub provisioning ---------------------------------------------------------------------

step "3. Hub provisioning for $CURRENCY"
if [[ $PROVISION_HUB != true ]]; then
  skip "PROVISION_HUB=$PROVISION_HUB, assuming the hub is already provisioned"
else
  hub_call "hub account HUB_MULTILATERAL_SETTLEMENT/$CURRENCY" POST "$HUB_ADMIN_URL/participants/$HUB_NAME/accounts" \
    "{\"type\":\"HUB_MULTILATERAL_SETTLEMENT\",\"currency\":\"$CURRENCY\"}"
  hub_call "hub account HUB_RECONCILIATION/$CURRENCY" POST "$HUB_ADMIN_URL/participants/$HUB_NAME/accounts" \
    "{\"type\":\"HUB_RECONCILIATION\",\"currency\":\"$CURRENCY\"}"
  hub_call "settlement model DEFERREDNET$CURRENCY" POST "$HUB_ADMIN_URL/settlementModels" "$(jq -n --arg c "$CURRENCY" '{
      name: ("DEFERREDNET" + $c), settlementGranularity: "NET", settlementInterchange: "MULTILATERAL",
      settlementDelay: "DEFERRED", requireLiquidityCheck: true, ledgerAccountType: "POSITION",
      settlementAccountType: "SETTLEMENT", autoPositionReset: true, currency: $c }')" \
    || skip "a default settlement model may already cover $CURRENCY"
  if [[ -n $ORACLE_URL ]]; then
    http GET "$HUB_ALS_ADMIN_URL/oracles"
    if jq -e --arg c "$CURRENCY" 'any(.[]?; .oracleIdType == "MSISDN" and (.currency == $c or .isDefault))' <<<"$BODY" >/dev/null 2>&1; then
      skip "ALS already has an MSISDN oracle"
    else
      hub_call "ALS oracle MSISDN/$CURRENCY -> $ORACLE_URL" POST "$HUB_ALS_ADMIN_URL/oracles" "$(jq -n --arg u "$ORACLE_URL" --arg c "$CURRENCY" '{
          oracleIdType: "MSISDN", endpoint: { value: $u, endpointType: "URL" }, currency: $c, isDefault: true }')"
    fi
  else
    skip "ORACLE_URL empty, assuming the ALS already has an MSISDN oracle"
  fi
fi

# --- 4. DFSPs: app + hub onboarding ----------------------------------------------------------

step "4. DFSPs"
for s in "${DFSPS[@]}"; do
  spec "$s"
  echo "  ${BOLD}$FSP${RESET}"
  http POST "$APP_URL/api/admin/dfsps" "$(jq -n --arg f "$FSP" --arg c "$CURRENCY" --arg o "$OUTBOUND" \
    --argjson ndc "$NET_DEBIT_CAP" --argjson funds "$INITIAL_FUNDS" '{
      fspId: $f, displayName: $f, currency: $c, sdkOutboundUrl: $o, netDebitCap: $ndc, initialPosition: 0,
      initialFunds: $funds }')" "$ADMIN"
  if is_2xx; then ok "created in the app, SDK outbound $OUTBOUND"
  elif [[ $STATUS == 409 ]]; then
    http PUT "$APP_URL/api/admin/dfsps/$FSP" "$(jq -n --arg o "$OUTBOUND" '{sdkOutboundUrl: $o}')" "$ADMIN"
    is_2xx && skip "already in the app, SDK outbound set to $OUTBOUND" || warn "updating $FSP failed: HTTP $STATUS $BODY"
  else die "creating $FSP in the app failed: HTTP $STATUS $BODY"
  fi
  http POST "$APP_URL/api/admin/dfsps/$FSP/onboard" "" "$ADMIN"
  if is_2xx; then
    http GET "$APP_URL/api/admin/dfsps/$FSP" "" "$ADMIN"
    ok "onboarded in the hub, endpoints -> $(jq -r .sdkInboundUrl <<<"$BODY")"
  else
    warn "onboarding $FSP failed: HTTP $STATUS $BODY"
  fi
done

# --- 5. demo customers -----------------------------------------------------------------------

step "5. Demo customers"
for s in "${DFSPS[@]}"; do
  spec "$s"
  T=$(token "$USER" "$PASS")
  [[ -n $T ]] || { warn "cannot get a token for $USER, skipping $FSP customers"; continue; }

  demo_customer "$FSP" "$T" "$NAME" "$MSISDN" "$DEPOSIT"
done

# --- 6. checks -------------------------------------------------------------------------------

step "6. Checks"
spec "${DFSPS[0]}"; PAYER_FSP=$FSP; PAYER_USER=$USER; PAYER_PASS=$PASS; PAYER_MSISDN=$MSISDN
spec "${DFSPS[1]}"; PAYEE_FSP=$FSP; PAYEE_MSISDN=$MSISDN
PAYER=$(token "$PAYER_USER" "$PAYER_PASS")

http GET "$APP_URL/api/parties/lookup/MSISDN/$PAYEE_MSISDN" "" "$PAYER"
if is_2xx && [[ $(jq -r .fspId <<<"$BODY") == "$PAYEE_FSP" ]]; then
  ok "discovery: $PAYER_FSP found MSISDN $PAYEE_MSISDN at $PAYEE_FSP ($(jq -r .name <<<"$BODY"))"
else
  warn "discovery of $PAYEE_MSISDN from $PAYER_FSP failed: HTTP $STATUS $BODY"
  TEST_TRANSFER=false
fi

if [[ $TEST_TRANSFER == true ]]; then
  http POST "$APP_URL/api/transfers" "{\"from\":{\"idType\":\"MSISDN\",\"idValue\":\"$PAYER_MSISDN\"},
    \"to\":{\"idType\":\"MSISDN\",\"idValue\":\"$PAYEE_MSISDN\"},\"amount\":$TEST_AMOUNT,\"currency\":\"$CURRENCY\",
    \"note\":\"setup check\",\"autoAccept\":true}" "$PAYER"
  if ! is_2xx; then
    warn "test transfer rejected: HTTP $STATUS $BODY"
  else
    STATE=$(jq -r .state <<<"$BODY")
    TRANSFER_ID=$(jq -r .transferId <<<"$BODY")
    if [[ $STATE == COMMITTED ]]; then
      ok "test transfer $TRANSFER_ID: $TEST_AMOUNT $CURRENCY $PAYER_FSP -> $PAYEE_FSP COMMITTED"
    else
      warn "test transfer $TRANSFER_ID ended in $STATE: $(jq -r '"\(.errorCode // "") \(.errorDescription // "")"' <<<"$BODY")"
    fi
  fi
fi

# --- 7. G2P disbursement --------------------------------------------------------------------

if [[ $TEST_DISBURSEMENT == true ]]; then
  step "7. G2P disbursement"
  demo_customer "$PAYER_FSP" "$PAYER" "$G2P_NAME" "$G2P_MSISDN" "$G2P_DEPOSIT"
  ITEMS=$(jq -cn --arg list "${G2P_BENEFICIARIES:-$PAYEE_MSISDN:250}" '$list | split(",") | map(split(":")
    | {to: {idType: "MSISDN", idValue: .[0]}, amount: .[1]})')
  http POST "$APP_URL/api/disbursements" "$(jq -cn --arg m "$G2P_MSISDN" --argjson items "$ITEMS" \
    --arg ref "setup check $(date +%F)" '{from: {idType: "MSISDN", idValue: $m}, reference: $ref, items: $items}')" "$PAYER"
  if ! is_2xx; then
    warn "disbursement rejected: HTTP $STATUS $BODY"
  else
    G2P_ID=$(jq -r .disbursementId <<<"$BODY")
    http PUT "$APP_URL/api/disbursements/$G2P_ID/validate" "" "$PAYER"
    if ! await_disbursement "$G2P_ID" "$PAYER" 90 VALIDATED; then
      warn "disbursement $G2P_ID not validated: $(jq -r .state <<<"$BODY")"
    else
      http GET "$APP_URL/api/disbursements/$G2P_ID/items?state=INVALID" "" "$PAYER"
      jq -r '.[] | "line \(.lineNo) \(.payee.idValue): \(.errorCode) \(.errorDescription)"' <<<"$BODY" \
        | while read -r line; do warn "invalid payee, not paid: $line"; done
      http PUT "$APP_URL/api/disbursements/$G2P_ID/approve" "" "$PAYER"
      if ! is_2xx; then
        warn "disbursement $G2P_ID not approved: HTTP $STATUS $BODY"
      elif await_disbursement "$G2P_ID" "$PAYER" 180 COMPLETED PARTIALLY_COMPLETED FAILED; then
        SUMMARY=$(jq -r '"\(.state): \(.committedCount)/\(.itemCount) paid, \(.committedAmount) of \(.totalAmount) \(.currency)"' <<<"$BODY")
        if [[ $(jq -r .state <<<"$BODY") == COMPLETED ]]; then ok "disbursement $G2P_ID $SUMMARY"
        else warn "disbursement $G2P_ID $SUMMARY (GET /api/disbursements/$G2P_ID/items)"
        fi
      else
        warn "disbursement $G2P_ID still $(jq -r .state <<<"$BODY") after 180s"
      fi
    fi
  fi
fi

# --- summary ---------------------------------------------------------------------------------

step "Done"
if [[ $WARNINGS == 0 ]]; then ok "sdk_adapter is ready"; else warn "finished with $WARNINGS warning(s); see above"; fi
cat <<EOF

  Log in (password grant, no client secret):
    curl -s $KEYCLOAK_URL/realms/$KEYCLOAK_REALM/protocol/openid-connect/token \\
      -d grant_type=password -d client_id=$CLIENT_ID -d username=$PAYER_USER -d password=$PAYER_PASS | jq -r .access_token
  API docs: $APP_URL/q/swagger-ui
EOF
[[ $WARNINGS == 0 ]]
