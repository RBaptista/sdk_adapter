package cv.sitech.application.service;

import java.util.List;

import cv.sitech.application.port.in.AccountUseCase;
import cv.sitech.application.port.in.PartyUseCase;
import cv.sitech.application.port.out.HubAdminGateway;
import cv.sitech.application.port.out.PartyRepository;
import cv.sitech.application.port.out.SdkOutboundGateway;
import cv.sitech.domain.Account;
import cv.sitech.domain.AccountTypeEnum;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.Party;
import cv.sitech.domain.exception.BusinessException;
import cv.sitech.domain.exception.NotFoundException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PartyService implements PartyUseCase {

  private final PartyRepository partyRepository;
  private final AccountUseCase accountUseCase;
  private final HubAdminGateway hubAdminGateway;
  private final SdkOutboundGateway sdk;

  public PartyService(PartyRepository partyRepository, AccountUseCase accountUseCase,
      HubAdminGateway hubAdminGateway, SdkOutboundGateway sdk) {
    this.partyRepository = partyRepository;
    this.accountUseCase = accountUseCase;
    this.hubAdminGateway = hubAdminGateway;
    this.sdk = sdk;
  }

  @Override
  public Party create(String fspId, IdTypeEnum idType, String idValue, Long accountId) {
    Party party = QuarkusTransaction.joiningExisting().call(() -> {
      Account account = accountUseCase.findById(fspId, accountId);
      if (account.getType() != AccountTypeEnum.CUSTOMER) {
        throw new IllegalArgumentException("Parties map to CUSTOMER accounts");
      }
      if (partyRepository.findByIdentifier(idType, idValue).isPresent()) {
        throw new BusinessException("3003", "Party " + idType + "/" + idValue + " already exists");
      }
      return partyRepository.save(new Party(idType, idValue, account));
    });
    // the ALS oracle must know which DFSP hosts the identifier before anyone can discover it; the SDK
    // registers it (POST /participants) and waits for the ALS callback
    try {
      sdk.registerParty(fspId, idType, idValue, party.getAccount().getDfsp().getCurrency().name());
    } catch (RuntimeException e) {
      QuarkusTransaction.joiningExisting().run(() -> partyRepository.deleteById(party.getId()));
      throw e;
    }
    return party;
  }

  @Override
  public Party findById(String fspId, Long id) {
    return partyRepository.findById(id)
        .filter(p -> fspId.equals(p.getAccount().getDfsp().getFspId()))
        .orElseThrow(() -> new NotFoundException("Party " + id + " not Found"));
  }

  @Override
  public List<Party> findAll(String fspId) {
    return partyRepository.findByDfsp(fspId);
  }

  @Override
  public Party register(String fspId, Long id) {
    Party party = QuarkusTransaction.joiningExisting().call(() -> findById(fspId, id));
    sdk.registerParty(fspId, party.getIdType(), party.getIdValue(),
        party.getAccount().getDfsp().getCurrency().name());
    return party;
  }

  @Override
  public void delete(String fspId, Long id) {
    Party party = QuarkusTransaction.joiningExisting().call(() -> {
      Party found = findById(fspId, id);
      partyRepository.deleteById(id);
      return found;
    });
    hubAdminGateway.deregisterParty(fspId, party.getIdType(), party.getIdValue());
  }

}
