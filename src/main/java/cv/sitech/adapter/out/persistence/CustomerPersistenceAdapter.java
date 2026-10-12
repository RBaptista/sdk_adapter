package cv.sitech.adapter.out.persistence;

import java.util.List;

import cv.sitech.adapter.out.entity.CustomerEntity;
import cv.sitech.adapter.out.persistence.mapper.CustomerMapper;
import cv.sitech.application.port.out.CustomerRepository;
import cv.sitech.domain.Customer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class CustomerPersistenceAdapter extends PanacheAdapter<Customer, CustomerEntity, Long> implements CustomerRepository {

  protected CustomerPersistenceAdapter() {
    super(null, null);
  }

  @Inject
  public CustomerPersistenceAdapter(CustomerPanacheRepository panache, CustomerMapper mapper) {
    super(panache, mapper);
  }

  @Override
  public List<Customer> findByDfsp(String fspId) {
    return list("dfspId", fspId);
  }

}
