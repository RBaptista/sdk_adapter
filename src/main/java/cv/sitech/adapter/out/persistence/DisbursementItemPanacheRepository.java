package cv.sitech.adapter.out.persistence;

import cv.sitech.adapter.out.entity.DisbursementItemEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DisbursementItemPanacheRepository implements PanacheRepository<DisbursementItemEntity> {

}
