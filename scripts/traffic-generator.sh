#!/usr/bin/env bash
#
# Background P2P, G2P and settlement traffic through sdk_adapter, for watching the hub in Prometheus/Grafana.
# The money moves in a closed loop, so it can run forever without top-ups or settlement:
#
#   P2P ping-pong   A (sdkbank) pays B (sdkwallet) random amounts until A is drained, then B pays
#                   A back until B is drained, and so on.
#   G2P             every G2P_EVERY seconds the government (sdkbank) pays a batch to G2P_BENEFICIARIES:
#                   B plus G2P_NEW_BENEFICIARIES sdkwallet customers that the script creates if missing.
#   REPAY           while the government is below GOV_TARGET, REPAY_PCT of the P2P ticks are
#                   a beneficiary paying the government back, little by little.
#   SETTLE          every SETTLE_EVERY seconds the open settlement window is closed and settled through
#                   central-settlement (PS_TRANSFERS_RECORDED → RESERVED → COMMITTED → SETTLED), then
#                   sdk_adapter reconciles it.
#
# Needs the demo data of ./scripts/setup-sdk-adapter.sh. Every MSISDN must be a party of sdkbank or
# sdkwallet; the script finds out which. Stop it with Ctrl-C.
#
# Usage:   ./scripts/traffic-generator.sh
#          ENABLE_G2P=false MIN_DELAY=0 MAX_DELAY=1 ./scripts/traffic-generator.sh
#          G2P_EVERY=20 FAIL_PCT=10 ./scripts/traffic-generator.sh
#
# Needs: bash, curl, jq.

set -uo pipefail

# --- configuration ---------------------------------------------------------------------------

APP_URL="${APP_URL:-http://localhost:8091}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8080}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-sdk}"
CLIENT_ID="${CLIENT_ID:-sdk-adapter}"
CURRENCY="${CURRENCY:-CVE}"

BANK_USER="${BANK_USER:-sdkbank-user}";       BANK_PASS="${BANK_PASS:-sdkbank}"
WALLET_USER="${WALLET_USER:-sdkwallet-user}"; WALLET_PASS="${WALLET_PASS:-sdkwallet}"

A_MSISDN="${A_MSISDN:-2389930000}"      # P2P side A (Maria, sdkbank)
B_MSISDN="${B_MSISDN:-2389940000}"      # P2P side B (Joao, sdkwallet)
GOV_MSISDN="${GOV_MSISDN:-2389950000}"  # G2P payer (Ministerio das Financas, sdkbank)

ENABLE_P2P="${ENABLE_P2P:-true}"
MIN_AMOUNT="${MIN_AMOUNT:-10}"
MAX_AMOUNT="${MAX_AMOUNT:-500}"
MIN_DELAY="${MIN_DELAY:-2}"             # seconds between P2P transfers
MAX_DELAY="${MAX_DELAY:-10}"
REPAY_PCT="${REPAY_PCT:-30}"            # share of P2P ticks that repay the government while it is short
GOV_TARGET="${GOV_TARGET:-100000}"      # the balance repayments bring the government back to (setup's G2P_DEPOSIT)

ENABLE_G2P="${ENABLE_G2P:-true}"
G2P_EVERY="${G2P_EVERY:-60}"            # seconds between batches (±25%)
G2P_ITEMS="${G2P_ITEMS:-3}"            # capped by the number of beneficiaries: a batch can't repeat a payee
G2P_MIN="${G2P_MIN:-50}"
G2P_MAX="${G2P_MAX:-500}"
G2P_NEW_BENEFICIARIES="${G2P_NEW_BENEFICIARIES:-4}"  # sdkwallet customers 2389960001.. created if missing
G2P_BENEFICIARIES="${G2P_BENEFICIARIES:-$B_MSISDN$(for ((n = 1; n <= G2P_NEW_BENEFICIARIES; n++)); do
  printf ',23899600%02d' "$n"; done)}"                 # comma-separated MSISDNs

ENABLE_SETTLEMENT="${ENABLE_SETTLEMENT:-true}"
SETTLE_EVERY="${SETTLE_EVERY:-300}"     # seconds between settlement windows
SETTLE_STEP_DELAY="${SETTLE_STEP_DELAY:-5}"  # seconds between settlement states, so dashboards show each one
SETTLEMENT_MODEL="${SETTLEMENT_MODEL:-DEFERREDNET$CURRENCY}"
HUB_ADMIN_URL="${HUB_ADMIN_URL:-http://localhost:3001}"            # central-ledger admin API
HUB_SETTLEMENT_URL="${HUB_SETTLEMENT_URL:-http://localhost:3007/v2}"  # central-settlement API
APP_ADMIN_USER="${APP_ADMIN_USER:-sdk-admin}"; APP_ADMIN_PASSWORD="${APP_ADMIN_PASSWORD:-admin}"

FAIL_PCT="${FAIL_PCT:-0}"               # share of P2P payees and G2P items sent to an unknown MSISDN

# --- helpers ---------------------------------------------------------------------------------

BOLD=$'\e[1m'; GREEN=$'\e[32m'; YELLOW=$'\e[33m'; RED=$'\e[31m'; CYAN=$'\e[36m'; RESET=$'\e[0m'

die() { printf '%s✗ %s%s\n' "$RED" "$*" "$RESET" >&2; exit 1; }
log() { printf '%s %-5s %s\n' "$(date +%T)" "$1" "${*:2}"; }

# each loop runs in its own process, with its own response file
RESPONSE_FILE=$(mktemp)

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

token() {
  curl -s "$KEYCLOAK_URL/realms/$KEYCLOAK_REALM/protocol/openid-connect/token" \
    -d grant_type=password -d client_id="$CLIENT_ID" -d username="$1" -d password="$2" | jq -r '.access_token // empty'
}

# tokens per DFSP, fetched again after 4 minutes (Keycloak's default lifetime is 5) or on a 401
declare -A TOKENS TOKEN_AT
auth() {
  local fsp=$1
  if [[ -z ${TOKENS[$fsp]:-} ]] || ((SECONDS - TOKEN_AT[$fsp] > 240)); then
    case $fsp in
      sdkbank)   TOKENS[$fsp]=$(token "$BANK_USER" "$BANK_PASS") ;;
      sdkwallet) TOKENS[$fsp]=$(token "$WALLET_USER" "$WALLET_PASS") ;;
      admin)     TOKENS[$fsp]=$(token "$APP_ADMIN_USER" "$APP_ADMIN_PASSWORD") ;;
    esac
    TOKEN_AT[$fsp]=$SECONDS
  fi
  TOKEN=${TOKENS[$fsp]}
}

# api FSP METHOD PATH [BODY]: http as that DFSP, retried once with a fresh token on a 401
api() {
  local fsp=$1 method=$2 path=$3 data=${4:-}
  auth "$fsp"; http "$method" "$APP_URL$path" "$data" "$TOKEN"
  if [[ $STATUS == 401 ]]; then
    TOKENS[$fsp]=; auth "$fsp"; http "$method" "$APP_URL$path" "$data" "$TOKEN"
  fi
}

# rand MIN MAX: a random integer in [MIN, MAX]
rand() { local lo=$1 hi=$2; ((hi < lo)) && hi=$lo; echo $((lo + (RANDOM * 32768 + RANDOM) % (hi - lo + 1))); }
chance() { (($(rand 1 100) <= $1)); }
min() { local m=$1; for v in "$@"; do ((v < m)) && m=$v; done; echo "$m"; }
unknown_msisdn() { echo "23899$(rand 10000 99999)"; }

# nap SECONDS: a sleep that a TERM interrupts at once
nap() { sleep "$1" & wait $!; }

declare -A FSP_OF ACCOUNT_OF

# balance MSISDN: its available balance, rounded down (empty if it can't be read)
balance() {
  api "${FSP_OF[$1]}" GET "/api/accounts/${ACCOUNT_OF[$1]}"
  is_2xx && jq -r '.availableBalance | floor' <<<"$BODY"
}

# pay FROM TO AMOUNT: one auto-accepted P2P transfer; sets RESULT ("COMMITTED" or "ABORTED 3204 ...") and MS
pay() {
  local from=$1 to=$2 amount=$3 start=$EPOCHREALTIME
  api "${FSP_OF[$from]}" POST /api/transfers "$(jq -cn --arg f "$from" --arg t "$to" --argjson a "$amount" \
    --arg c "$CURRENCY" '{from: {idType: "MSISDN", idValue: $f}, to: {idType: "MSISDN", idValue: $t},
    amount: $a, currency: $c, note: "traffic generator", autoAccept: true}')"
  MS=$(((${EPOCHREALTIME//[.,]/} - ${start//[.,]/}) / 1000))
  if is_2xx; then RESULT=$(jq -r '[.state, .errorCode, .errorDescription] | map(select(. != null)) | join(" ")' <<<"$BODY")
  else RESULT="HTTP $STATUS $(head -c 200 <<<"$BODY")"
  fi
}

colour() { [[ $1 == COMMITTED* || $1 == COMPLETED* || $1 == MATCH ]] && echo "$GREEN" || echo "$RED"; }

# --- P2P loop --------------------------------------------------------------------------------

p2p_loop() {
  local sent=0 committed=0 failed=0 repaid=0 flips=0 from=$A_MSISDN to=$B_MSISDN
  RESPONSE_FILE=$(mktemp)
  trap 'rm -f "$RESPONSE_FILE"; printf "%sP2P:%s %d sent, %d committed, %d failed, %d repayments, %d flips\n" "$BOLD" "$RESET" \
    $sent $committed $failed $repaid $flips; exit 0' TERM
  local beneficiaries; IFS=',' read -ra beneficiaries <<<"$G2P_BENEFICIARIES"
  while true; do
    local kind=P2P payer=$from payee=$to amount=

    # repay the government, little by little, while it is short of its starting balance
    if [[ $ENABLE_G2P == true ]] && chance "$REPAY_PCT"; then
      local gov deficit b bal
      gov=$(balance "$GOV_MSISDN")
      deficit=$((GOV_TARGET - ${gov:-$GOV_TARGET}))
      if ((deficit >= MIN_AMOUNT)); then
        b=${beneficiaries[$(rand 0 $((${#beneficiaries[@]} - 1)))]}
        bal=$(balance "$b")
        if ((${bal:-0} >= MIN_AMOUNT)); then
          kind=REPAY payer=$b payee=$GOV_MSISDN
          amount=$(rand "$MIN_AMOUNT" "$(min "$MAX_AMOUNT" "$deficit" "$bal")")
        fi
      fi
    fi

    # ping-pong: the payer pays until it is drained, then the other side pays back
    if [[ -z $amount ]]; then
      local bal; bal=$(balance "$from")
      if ((${bal:-0} < MIN_AMOUNT)); then
        local tmp=$from; from=$to; to=$tmp; flips=$((flips + 1))
        log FLIP "${CYAN}$tmp drained → $from pays back${RESET}"
        bal=$(balance "$from")
        if ((${bal:-0} < MIN_AMOUNT)); then
          log WAIT "${YELLOW}$A_MSISDN and $B_MSISDN both below $MIN_AMOUNT $CURRENCY${RESET}"
          nap "$MAX_DELAY"; continue
        fi
        payer=$from payee=$to
      fi
      amount=$(rand "$MIN_AMOUNT" "$(min "$MAX_AMOUNT" "$bal")")
    fi

    chance "$FAIL_PCT" && payee=$(unknown_msisdn)
    pay "$payer" "$payee" "$amount"
    sent=$((sent + 1))
    if [[ $RESULT == COMMITTED ]]; then
      committed=$((committed + 1)); [[ $kind == REPAY ]] && repaid=$((repaid + 1))
    else failed=$((failed + 1))
    fi
    log "$kind" "$payer → $payee $(printf '%6s' "$amount") $CURRENCY  $(colour "$RESULT")$RESULT$RESET  ${MS}ms" \
      " | A $(balance "$A_MSISDN")  B $(balance "$B_MSISDN")  gov $(balance "$GOV_MSISDN")"
    nap "$(rand "$MIN_DELAY" "$MAX_DELAY")"
  done
}

# --- G2P loop --------------------------------------------------------------------------------

# await_batch ID TIMEOUT STATE...: polls until the batch is in one of STATE; sets BODY
await_batch() {
  local id=$1 timeout=$2; shift 2
  local deadline=$((SECONDS + timeout)) state
  while ((SECONDS < deadline)); do
    api sdkbank GET "/api/disbursements/$id"
    state=$(jq -r .state <<<"$BODY" 2>/dev/null)
    for wanted in "$@"; do [[ $state == "$wanted" ]] && return 0; done
    nap 2
  done
  return 1
}

g2p_loop() {
  local batches=0 completed=0 partial=0 failed=0 skipped=0 paid=0 paid_amount=0
  RESPONSE_FILE=$(mktemp)
  trap 'rm -f "$RESPONSE_FILE"; printf "%sG2P:%s %d batches (%d completed, %d partial, %d failed), %d skipped, %d items paid, %d %s\n" \
    "$BOLD" "$RESET" $batches $completed $partial $failed $skipped $paid $paid_amount "$CURRENCY"; exit 0' TERM
  local beneficiaries; IFS=',' read -ra beneficiaries <<<"$G2P_BENEFICIARIES"
  while true; do
    nap "$(rand $((G2P_EVERY * 3 / 4)) $((G2P_EVERY * 5 / 4)))"

    local gov; gov=$(balance "$GOV_MSISDN")
    if ((${gov:-0} < $(min "$G2P_ITEMS" "${#beneficiaries[@]}") * G2P_MAX)); then
      skipped=$((skipped + 1))
      log G2P "${YELLOW}skipped: government has ${gov:-?} $CURRENCY, a batch may need $((G2P_ITEMS * G2P_MAX))${RESET}"
      continue
    fi

    local items='[]' to
    for to in $(printf '%s\n' "${beneficiaries[@]}" | shuf -n "$G2P_ITEMS"); do
      chance "$FAIL_PCT" && to=$(unknown_msisdn)
      items=$(jq -c --arg t "$to" --argjson a "$(rand "$G2P_MIN" "$G2P_MAX")" \
        '. + [{to: {idType: "MSISDN", idValue: $t}, amount: $a}]' <<<"$items")
    done

    local start=$SECONDS id state summary
    batches=$((batches + 1))
    api sdkbank POST /api/disbursements "$(jq -cn --arg m "$GOV_MSISDN" --arg c "$CURRENCY" --argjson items "$items" \
      --arg ref "traffic $(date +%FT%T)" '{from: {idType: "MSISDN", idValue: $m}, currency: $c, reference: $ref,
      items: $items}')"
    if ! is_2xx; then
      failed=$((failed + 1)); log G2P "${RED}create failed: HTTP $STATUS $(head -c 200 <<<"$BODY")${RESET}"; continue
    fi
    id=$(jq -r .disbursementId <<<"$BODY")
    log G2P "batch $id: $(jq length <<<"$items") items, $(jq '[.[].amount] | add' <<<"$items") $CURRENCY, validating"

    api sdkbank PUT "/api/disbursements/$id/validate"
    if ! await_batch "$id" 180 VALIDATED FAILED CANCELLED || [[ $(jq -r .state <<<"$BODY") != VALIDATED ]]; then
      failed=$((failed + 1)); log G2P "${RED}batch $id not validated: $(jq -r .state <<<"$BODY")${RESET}"
      api sdkbank PUT "/api/disbursements/$id/cancel"; continue
    fi
    api sdkbank PUT "/api/disbursements/$id/approve"
    if ! is_2xx; then
      failed=$((failed + 1)); log G2P "${RED}batch $id not approved: HTTP $STATUS $(head -c 200 <<<"$BODY")${RESET}"
      api sdkbank PUT "/api/disbursements/$id/cancel"; continue
    fi
    if ! await_batch "$id" 300 COMPLETED PARTIALLY_COMPLETED FAILED; then
      failed=$((failed + 1)); log G2P "${RED}batch $id still $(jq -r .state <<<"$BODY") after 300s${RESET}"; continue
    fi

    state=$(jq -r .state <<<"$BODY")
    case $state in
      COMPLETED) completed=$((completed + 1)) ;;
      PARTIALLY_COMPLETED) partial=$((partial + 1)) ;;
      *) failed=$((failed + 1)) ;;
    esac
    paid=$((paid + $(jq -r .committedCount <<<"$BODY")))
    paid_amount=$((paid_amount + $(jq -r '.committedAmount // 0 | floor' <<<"$BODY")))
    summary=$(jq -r '"\(.committedCount)/\(.itemCount) paid, \(.committedAmount) of \(.totalAmount)"' <<<"$BODY")
    log G2P "batch $id $(colour "$state")$state$RESET: $summary $CURRENCY, $((SECONDS - start))s | gov $(balance "$GOV_MSISDN")"
  done
}

# --- settlement loop -------------------------------------------------------------------------

declare -A ACCOUNT_NAME

# account_names: hub account id -> participant name, for the settlement log lines
account_names() {
  local name
  http GET "$HUB_ADMIN_URL/participants"
  for name in $(jq -r '.[]?.name' <<<"$BODY" 2>/dev/null); do
    http GET "$HUB_ADMIN_URL/participants/$name/accounts"
    while read -r id; do ACCOUNT_NAME[$id]=$name; done < <(jq -r '.[]?.id' <<<"$BODY" 2>/dev/null)
  done
}

# await_window ID TIMEOUT STATE: polls until the settlement window is in STATE; sets BODY
await_window() {
  local id=$1 deadline=$((SECONDS + $2)) state=$3
  while ((SECONDS < deadline)); do
    http GET "$HUB_SETTLEMENT_URL/settlementWindows/$id"
    [[ $(jq -r .state <<<"$BODY" 2>/dev/null) == "$state" ]] && return 0
    nap 2
  done
  return 1
}

settle_loop() {
  local closed=0 settlements=0 settled=0 failed=0 matched=0 mismatched=0
  RESPONSE_FILE=$(mktemp)
  trap 'rm -f "$RESPONSE_FILE"; printf "%sSETTLE:%s %d windows closed, %d settlements (%d settled, %d failed), reconciliation %d matched, %d mismatched\n" \
    "$BOLD" "$RESET" $closed $settlements $settled $failed $matched $mismatched; exit 0' TERM
  account_names
  while true; do
    nap "$(rand $((SETTLE_EVERY * 9 / 10)) $((SETTLE_EVERY * 11 / 10)))"

    # close the open window; the hub opens the next one at once
    local open next start=$SECONDS
    http GET "$HUB_SETTLEMENT_URL/settlementWindows?state=OPEN"
    open=$(jq -r 'if type == "array" then first(.[].settlementWindowId) // empty else empty end' <<<"$BODY" 2>/dev/null)
    if [[ -z $open ]]; then log SETTLE "${RED}no open settlement window: HTTP $STATUS $(head -c 200 <<<"$BODY")${RESET}"; continue; fi
    http POST "$HUB_SETTLEMENT_URL/settlementWindows/$open" '{"state":"CLOSED","reason":"traffic generator"}'
    if ! is_2xx; then
      failed=$((failed + 1)); log SETTLE "${RED}closing window $open failed: HTTP $STATUS $(head -c 200 <<<"$BODY")${RESET}"; continue
    fi
    next=$(jq -r .settlementWindowId <<<"$BODY")
    # central-settlement closes it asynchronously (PROCESSING, then CLOSED)
    if ! await_window "$open" 120 CLOSED; then
      failed=$((failed + 1)); log SETTLE "${RED}window $open still $(jq -r .state <<<"$BODY") after 120s${RESET}"; continue
    fi
    closed=$((closed + 1))
    log SETTLE "window $open closed in $((SECONDS - start))s, window $next open"

    # settle every closed window with unsettled content, including ones an earlier failure left behind
    local windows
    http GET "$HUB_SETTLEMENT_URL/settlementWindows?state=CLOSED"
    windows=$(jq -c 'if type == "array" then [.[] | select(any(.content[]?; .settlementId == null))
      | {id: .settlementWindowId}] else [] end' <<<"$BODY" 2>/dev/null)
    if [[ ${windows:-[]} == '[]' ]]; then log SETTLE "window $open had no transfers, nothing to settle"; continue; fi

    local settlement id net st body
    http POST "$HUB_SETTLEMENT_URL/settlements" "$(jq -cn --arg m "$SETTLEMENT_MODEL" --argjson w "$windows" \
      '{settlementModel: $m, reason: "traffic generator", settlementWindows: $w}')"
    if ! is_2xx; then
      failed=$((failed + 1)); log SETTLE "${RED}creating the settlement failed: HTTP $STATUS $(head -c 200 <<<"$BODY")${RESET}"; continue
    fi
    settlements=$((settlements + 1))
    settlement=$BODY id=$(jq -r .id <<<"$BODY")
    [[ -z ${ACCOUNT_NAME[$(jq -r '.participants[0].accounts[0].id' <<<"$settlement")]:-} ]] && account_names
    net=$(jq -r '.participants[].accounts[] | "\(.id) \(.netSettlementAmount.amount)"' <<<"$settlement" |
      while read -r acc amount; do printf '%s %+g  ' "${ACCOUNT_NAME[$acc]:-account $acc}" "$amount"; done)
    log SETTLE "settlement $id for windows $(jq -r 'map(.id) | join(",")' <<<"$windows"): $net"

    for st in PS_TRANSFERS_RECORDED PS_TRANSFERS_RESERVED PS_TRANSFERS_COMMITTED SETTLED; do
      nap "$SETTLE_STEP_DELAY"
      body=$(jq -c --arg s "$st" '{participants: [.participants[] | {id, accounts: [.accounts[]
        | {id, state: $s, reason: "traffic generator", externalReference: "traffic-generator"}]}]}' <<<"$settlement")
      http PUT "$HUB_SETTLEMENT_URL/settlements/$id" "$body"
      if ! is_2xx || [[ $(jq -r .state <<<"$BODY") != "$st" ]]; then
        log SETTLE "${RED}settlement $id: $st failed: HTTP $STATUS $(head -c 200 <<<"$BODY")${RESET}"; break
      fi
      log SETTLE "settlement $id $st"
    done
    if [[ $(jq -r .state <<<"$BODY" 2>/dev/null) != SETTLED ]]; then failed=$((failed + 1)); continue; fi
    settled=$((settled + 1))
    log SETTLE "settlement $id ${GREEN}SETTLED${RESET} in $((SECONDS - start))s"

    # sdk_adapter compares the hub's settlement with its own ledger
    api admin POST /api/settlements/reconcile
    if ! is_2xx; then log SETTLE "${YELLOW}reconciliation failed: HTTP $STATUS${RESET}"; continue; fi
    local line status
    while IFS=$'\t' read -r status line; do
      if [[ $status == MATCH ]]; then matched=$((matched + 1)); else mismatched=$((mismatched + 1)); fi
      log RECON "settlement $id $line $(colour "$status")$status$RESET"
    done < <(jq -r --arg id "$id" '.[] | select(.kind == "SETTLEMENT" and .hubReference == $id)
      | [.status, "\(.fspId): hub \(.hubAmount), sdk_adapter \(.internalAmount)"] | @tsv' <<<"$BODY")
  done
}

# --- G2P beneficiaries -----------------------------------------------------------------------

# beneficiary MSISDN: an sdkwallet customer with an account and the MSISDN registered in the ALS
beneficiary() {
  local msisdn=$1 customer account
  api sdkwallet POST /api/customers "$(jq -cn --arg n "Beneficiario $msisdn" --arg m "$msisdn" \
    '{name: $n, contact: {phone: $m}}')"
  is_2xx || die "creating beneficiary $msisdn failed: HTTP $STATUS $BODY"
  customer=$(jq .id <<<"$BODY")
  api sdkwallet POST /api/accounts "{\"customerId\":$customer}"
  is_2xx || die "opening an account for beneficiary $msisdn failed: HTTP $STATUS $BODY"
  account=$(jq .id <<<"$BODY")
  api sdkwallet POST /api/parties "{\"idType\":\"MSISDN\",\"idValue\":\"$msisdn\",\"accountId\":$account}"
  is_2xx || die "registering beneficiary $msisdn failed: HTTP $STATUS $BODY"
  FSP_OF[$msisdn]=sdkwallet ACCOUNT_OF[$msisdn]=$account
  printf '  %s+%s created beneficiary %s on sdkwallet, account %s\n' "$GREEN" "$RESET" "$msisdn" "$account"
}

# --- main ------------------------------------------------------------------------------------

for tool in curl jq shuf; do command -v "$tool" >/dev/null || die "$tool is required"; done
[[ $ENABLE_P2P == true || $ENABLE_G2P == true || $ENABLE_SETTLEMENT == true ]] || die "nothing enabled"

for fsp in sdkbank sdkwallet; do
  api "$fsp" GET /api/parties
  is_2xx || die "$fsp: GET /api/parties failed: HTTP $STATUS (is the app at $APP_URL and Keycloak at $KEYCLOAK_URL up?)"
  while read -r msisdn account; do
    FSP_OF[$msisdn]=$fsp ACCOUNT_OF[$msisdn]=$account
  done < <(jq -r '.[] | select(.idType == "MSISDN") | "\(.idValue) \(.accountId)"' <<<"$BODY")
done

NEEDED=("$A_MSISDN" "$B_MSISDN")
[[ $ENABLE_G2P == true ]] && { IFS=',' read -ra B_LIST <<<"$G2P_BENEFICIARIES"; NEEDED+=("$GOV_MSISDN" "${B_LIST[@]}"); }
readarray -t NEEDED < <(printf '%s\n' "${NEEDED[@]}" | awk '!seen[$0]++')
for m in "${NEEDED[@]}"; do
  [[ -z ${FSP_OF[$m]:-} && $ENABLE_G2P == true && $m == 23899600* ]] && beneficiary "$m"
  [[ -n ${FSP_OF[$m]:-} ]] || die "MSISDN $m is not a party of sdkbank or sdkwallet (run ./scripts/setup-sdk-adapter.sh)"
done
[[ -n ${FSP_OF[$GOV_MSISDN]:-} ]] || ENABLE_G2P=false   # P2P only: no repayments

printf '%sTraffic generator%s  P2P %s  G2P %s  settlement %s  (Ctrl-C to stop)\n' "$BOLD" "$RESET" \
  "$ENABLE_P2P" "$ENABLE_G2P" "$ENABLE_SETTLEMENT"
for m in "${NEEDED[@]}"; do
  printf '  %-12s %-10s account %-5s %8s %s\n' "$m" "${FSP_OF[$m]}" "${ACCOUNT_OF[$m]}" "$(balance "$m")" "$CURRENCY"
done

PIDS=()
[[ $ENABLE_P2P == true ]] && { p2p_loop & PIDS+=($!); }
[[ $ENABLE_G2P == true ]] && { g2p_loop & PIDS+=($!); }
[[ $ENABLE_SETTLEMENT == true ]] && { settle_loop & PIDS+=($!); }

stop() {
  trap - INT TERM
  echo
  for pid in "${PIDS[@]}"; do pkill -TERM -P "$pid" 2>/dev/null; kill -TERM "$pid" 2>/dev/null; done
  wait
  rm -f "$RESPONSE_FILE"
  exit 0
}
trap stop INT TERM
wait
