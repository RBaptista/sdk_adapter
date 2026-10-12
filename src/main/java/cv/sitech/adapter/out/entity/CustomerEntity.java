package cv.sitech.adapter.out.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "customers")
public class CustomerEntity {
  private Long id;
  private String name;
  private ContactEntity contact;
  private String address;
  private Long nif;
  private String dfspId;

  @Id
  @GeneratedValue
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

  @OneToOne(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "contact_id", unique = true)
  public ContactEntity getContact() {
    return contact;
  }

  public void setContact(ContactEntity contact) {
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

  @Column(name = "dfsp_id")
  public String getDfspId() {
    return dfspId;
  }

  public void setDfspId(String dfspId) {
    this.dfspId = dfspId;
  }

}
