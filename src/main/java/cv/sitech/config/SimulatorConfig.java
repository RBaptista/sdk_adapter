package cv.sitech.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import cv.sitech.domain.disbursement.DisbursementMode;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "simulator")
public interface SimulatorConfig {

  /** grace period after expiry before a RESERVED transfer is resolved through the SDK or expired */
  Duration callbackTimeout();

  /** lifetime of quotes and transfers before they expire */
  Duration transferExpiry();

  Payee payee();

  Sdk sdk();

  Hub hub();

  Settlement settlement();

  Disbursement disbursement();

  interface Payee {
    /** state the payee answers a transfer prepare with: COMMITTED or RESERVED */
    @WithDefault("COMMITTED")
    String transferState();

    /** fee taken by the payee DFSP, in percent of the quoted amount */
    @WithDefault("0")
    BigDecimal feePercent();
  }

  /** the Mojaloop SDK scheme adapter that each hosted DFSP talks to the hub through */
  interface Sdk {
    /** how long a call to the SDK outbound API may take; above the SDK's REQUEST_PROCESSING_TIMEOUT_SECONDS */
    @WithDefault("60S")
    Duration requestTimeout();

    /** the SDK inbound API as the hub reaches it, used when a DFSP is created without one; <fspId> is replaced */
    @WithDefault("http://<fspId>-sdk:4000")
    String inboundUrlTemplate();
  }

  /** hub services the SDK doesn't cover: participant onboarding, ALS removal, settlement reads */
  interface Hub {
    String alsUrl();

    String adminUrl();

    String settlementUrl();

    Optional<String> bearerToken();
  }

  interface Settlement {
    /** scheduler interval of the reconciliation job, or "off" */
    @WithDefault("5m")
    String reconcileEvery();
  }

  /** G2P batches, each item paid as its own transfer */
  interface Disbursement {
    /** scheduler interval of the job that validates, executes and closes batches */
    @WithDefault("2s")
    String every();

    /** items validated or paid at once; each one holds an SDK call for up to sdk.request-timeout */
    @WithDefault("5")
    int parallelism();

    @WithDefault("10000")
    int maxItems();

    /** a SUBMITTED item with no transfer row after this long was never sent, and is queued again */
    @WithDefault("5M")
    Duration resubmitAfter();

    /** how new batches reach the hub: INDIVIDUAL transfers or the SDK's BULK transactions */
    @WithDefault("INDIVIDUAL")
    DisbursementMode mode();

    /** BULK: the SDK's bulkExpiration, how long it keeps the bulk transaction waiting for acceptance */
    @WithDefault("1H")
    Duration bulkExpiry();

    /** BULK: an item still waiting for the SDK's party or quote callback after this long fails with 2004 */
    @WithDefault("5M")
    Duration bulkTimeout();
  }

  default String sdkInboundUrl(String fspId) {
    return sdk().inboundUrlTemplate().replace("<fspId>", fspId);
  }
}
