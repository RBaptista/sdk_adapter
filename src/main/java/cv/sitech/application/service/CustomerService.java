package cv.sitech.application.service;

import java.util.List;

import cv.sitech.application.port.in.CustomerUseCase;
import cv.sitech.application.port.out.CustomerRepository;
import cv.sitech.domain.Contact;
import cv.sitech.domain.Customer;
import cv.sitech.domain.exception.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class CustomerService implements CustomerUseCase {

  private final CustomerRepository customerRepository;

  public CustomerService(CustomerRepository customerRepository) {
    this.customerRepository = customerRepository;
  }

  @Override
  @Transactional
  public Customer create(String fspId, String name, Contact contact, String address, Long nif) {
    return customerRepository.save(new Customer(name, contact, address, nif, fspId));
  }

  @Override
  public Customer findById(String fspId, Long id) {
    return customerRepository.findById(id)
        .filter(c -> fspId.equals(c.getDfspId()))
        .orElseThrow(() -> new NotFoundException("Customer " + id + " not Found"));
  }

  @Override
  public List<Customer> findAll(String fspId) {
    return customerRepository.findByDfsp(fspId);
  }

  @Override
  @Transactional
  public Customer update(String fspId, Long id, String name, Contact contact, String address, Long nif) {
    Customer customer = findById(fspId, id);
    customer.setName(name);
    if (contact != null && customer.getContact() != null) {
      // keep the existing contact row
      contact.setId(customer.getContact().getId());
    }
    customer.setContact(contact);
    customer.setAddress(address);
    customer.setNif(nif);
    return customerRepository.save(customer);
  }

}
