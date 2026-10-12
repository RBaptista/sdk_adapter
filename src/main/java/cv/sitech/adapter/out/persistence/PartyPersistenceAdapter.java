package cv.sitech.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import cv.sitech.adapter.out.entity.PartyEntity;
import cv.sitech.adapter.out.persistence.mapper.PartyMapper;
import cv.sitech.application.port.out.PartyRepository;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.Party;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class PartyPersistenceAdapter extends PanacheAdapter<Party, PartyEntity, Long> implements PartyRepository {

  protected PartyPersistenceAdapter() {
    super(null, null);
  }

  @Inject
  public PartyPersistenceAdapter(PartyPanacheRepository panache, PartyMapper mapper) {
    super(panache, mapper);
  }

  @Override
  public Optional<Party> findByIdentifier(IdTypeEnum idType, String idValue) {
    return first("idType = ?1 and idValue = ?2", idType.name(), idValue);
  }

  @Override
  public List<Party> findByDfsp(String fspId) {
    return list("account.dfsp.fspId", fspId);
  }

}
