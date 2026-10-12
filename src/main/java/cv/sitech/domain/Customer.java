package cv.sitech.domain;

public class Customer {
  private Long id;
  private String name;
  private Contact contact;
  private String address;
  private Long nif;
  private String dfspId;

  public Customer(String name, Contact contact, String address, Long nif, String dfspId) {
    this.name = name;
    this.contact = contact;
    this.address = address;
    this.nif = nif;
    this.dfspId = dfspId;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Contact getContact() {
    return contact;
  }

  public void setContact(Contact contact) {
    this.contact = contact;
  }

  public String getAddress() {
    return address;
  }

  public void setAddress(String address) {
    this.address = address;
  }

  public Long getNif() {
    return nif;
  }

  public void setNif(Long nif) {
    this.nif = nif;
  }

  public String getDfspId() {
    return dfspId;
  }

  public void setDfspId(String dfspId) {
    this.dfspId = dfspId;
  }

}
