package cv.sitech.application.port.out;

import java.util.List;
import java.util.Optional;

import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.Party;

public interface PartyRepository {
  Party save(Party party);

  Optional<Party> findById(Long id);

  Optional<Party> findByIdentifier(IdTypeEnum idType, String idValue);

  List<Party> findAll();

  List<Party> findByDfsp(String fspId);

  Boolean deleteById(Long id);

}
