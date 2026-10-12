package cv.sitech.application.service;

import static cv.sitech.application.service.LedgerService.posting;

import java.math.BigDecimal;
import java.util.List;

import cv.sitech.application.port.in.DfspUseCase;
import cv.sitech.application.port.out.AccountRepository;
import cv.sitech.application.port.out.DfspRepository;
import cv.sitech.application.port.out.HubAdminGateway;
import cv.sitech.config.SimulatorConfig;
import cv.sitech.domain.Account;
import cv.sitech.domain.AccountTypeEnum;
import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.Dfsp;
import cv.sitech.domain.exception.BusinessException;
import cv.sitech.domain.exception.NotFoundException;
import cv.sitech.domain.ledger.EntryType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class DfspService implements DfspUseCase {

  private final DfspRepository dfspRepository;
  private final AccountRepository accountRepository;
  private final LedgerService ledgerService;
  private final HubAdminGateway hubAdminGateway;
  private final SimulatorConfig config;

  public DfspService(DfspRepository dfspRepository, AccountRepository accountRepository, LedgerService ledgerService,
      HubAdminGateway hubAdminGateway, SimulatorConfig config) {
    this.dfspRepository = dfspRepository;
    this.accountRepository = accountRepository;
    this.ledgerService = ledgerService;
    this.hubAdminGateway = hubAdminGateway;
    this.config = config;
  }

  @Override
  @Transactional
  public Dfsp create(String fspId, String displayName, CurrencyEnum currency, String sdkOutboundUrl,
      String sdkInboundUrl, BigDecimal netDebitCap, BigDecimal initialPosition, BigDecimal initialFunds) {
    if (sdkOutboundUrl == null || sdkOutboundUrl.isBlank()) {
      throw new IllegalArgumentException("sdkOutboundUrl is required");
    }
    if (dfspRepository.findById(fspId).isPresent()) {
      throw new BusinessException("3003", "Dfsp " + fspId + " already exists");
    }
    Dfsp dfsp = dfspRepository.save(new Dfsp(fspId, displayName, currency,
        sdkInboundUrl != null ? sdkInboundUrl : config.sdkInboundUrl(fspId), sdkOutboundUrl, netDebitCap,
        initialPosition, initialFunds));
    Account position = accountRepository.save(new Account(AccountTypeEnum.POSITION, null, dfsp));
    Account settlement = accountRepository.save(new Account(AccountTypeEnum.SETTLEMENT, null, dfsp));
    if (dfsp.getInitialPosition().signum() != 0) {
      ledgerService.post("INITIAL_POSITION:" + fspId, EntryType.INITIAL_POSITION, List.of(
          posting(position.getId(), dfsp.getInitialPosition()),
          posting(settlement.getId(), dfsp.getInitialPosition().negate())));
    }
    return dfsp;
  }

  @Override
  public Dfsp findById(String fspId) {
    return dfspRepository.findById(fspId).orElseThrow(() -> new NotFoundException("Dfsp " + fspId + " not Found"));
  }

  @Override
  public List<Dfsp> findAll() {
    return dfspRepository.findAll();
  }

  @Override
  @Transactional
  public Dfsp update(String fspId, String displayName, String sdkOutboundUrl, String sdkInboundUrl,
      BigDecimal netDebitCap) {
    Dfsp dfsp = findById(fspId);
    if (displayName != null) {
      dfsp.setDisplayName(displayName);
    }
    if (sdkOutboundUrl != null) {
      dfsp.setSdkOutboundUrl(sdkOutboundUrl);
    }
    if (sdkInboundUrl != null) {
      dfsp.setSdkInboundUrl(sdkInboundUrl);
    }
    if (netDebitCap != null) {
      dfsp.setNetDebitCap(netDebitCap);
    }
    return dfspRepository.save(dfsp);
  }

  @Override
  public void onboard(String fspId) {
    Dfsp dfsp = findById(fspId);
    hubAdminGateway.onboard(dfsp);
    if (dfsp.getInitialFunds().signum() > 0) {
      hubAdminGateway.recordFundsIn(fspId, dfsp.getInitialFunds(), dfsp.getCurrency().name());
    }
  }

}
