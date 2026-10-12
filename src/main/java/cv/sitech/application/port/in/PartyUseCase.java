package cv.sitech.application.port.in;

import java.util.List;

import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.Party;

/** All operations are scoped to the DFSP {@code fspId}; parties are registered in the hub's ALS. */
public interface PartyUseCase {
  Party create(String fspId, IdTypeEnum idType, String idValue, Long accountId);

  Party findById(String fspId, Long id);

  List<Party> findAll(String fspId);

  void delete(String fspId, Long id);

  /** registers an existing party in the hub's ALS again, e.g. after the hub was reset */
  Party register(String fspId, Long id);
}
