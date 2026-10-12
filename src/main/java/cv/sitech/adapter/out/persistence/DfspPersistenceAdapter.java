package cv.sitech.adapter.out.persistence;

import cv.sitech.adapter.out.entity.DfspEntity;
import cv.sitech.adapter.out.persistence.mapper.DfspMapper;
import cv.sitech.application.port.out.DfspRepository;
import cv.sitech.domain.Dfsp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class DfspPersistenceAdapter extends PanacheAdapter<Dfsp, DfspEntity, String> implements DfspRepository {

  protected DfspPersistenceAdapter() {
    super(null, null);
  }

  @Inject
  public DfspPersistenceAdapter(DfspPanacheRepository panache, DfspMapper mapper) {
    super(panache, mapper);
  }

}
