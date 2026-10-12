package cv.sitech.adapter.out.persistence;

import cv.sitech.adapter.out.entity.PartyEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PartyPanacheRepository implements PanacheRepository<PartyEntity> {

}
