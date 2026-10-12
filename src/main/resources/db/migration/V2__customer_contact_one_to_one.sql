-- a contact belongs to exactly one customer
drop index idx_customers_contact_id;
alter table customers add constraint uk_customers_contact unique (contact_id);
