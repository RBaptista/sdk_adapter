package cv.sitech.adapter.out.persistence;

import cv.sitech.adapter.out.entity.CustomerEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CustomerPanacheRepository implements PanacheRepository<CustomerEntity> {

}
