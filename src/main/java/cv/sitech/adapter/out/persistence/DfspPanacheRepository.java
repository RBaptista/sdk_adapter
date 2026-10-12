package cv.sitech.adapter.out.persistence;

import cv.sitech.adapter.out.entity.DfspEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DfspPanacheRepository implements PanacheRepositoryBase<DfspEntity, String> {

}
