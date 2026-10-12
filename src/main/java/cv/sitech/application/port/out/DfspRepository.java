package cv.sitech.application.port.out;

import java.util.List;
import java.util.Optional;

import cv.sitech.domain.Dfsp;

public interface DfspRepository {
  Dfsp save(Dfsp dfsp);

  Optional<Dfsp> findById(String fspId);

  List<Dfsp> findAll();

  Boolean deleteById(String fspId);

}
