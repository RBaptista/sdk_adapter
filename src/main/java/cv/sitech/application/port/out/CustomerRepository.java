package cv.sitech.application.port.out;

import java.util.List;
import java.util.Optional;

import cv.sitech.domain.Customer;

public interface CustomerRepository {
  Customer save(Customer customer);

  Optional<Customer> findById(Long id);

  List<Customer> findAll();

  List<Customer> findByDfsp(String fspId);

  Boolean deleteById(Long id);

}
