package cv.sitech.application.port.in;

import java.util.List;

import cv.sitech.domain.Contact;
import cv.sitech.domain.Customer;

/** All operations are scoped to the DFSP {@code fspId}. */
public interface CustomerUseCase {
  Customer create(String fspId, String name, Contact contact, String address, Long nif);

  Customer findById(String fspId, Long id);

  List<Customer> findAll(String fspId);

  Customer update(String fspId, Long id, String name, Contact contact, String address, Long nif);

}
