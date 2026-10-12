-- hub traffic goes through a Mojaloop SDK scheme adapter per DFSP instead of FSPIOP callbacks to this app
alter table dfsp rename column callback_base_url to sdk_inbound_url;
alter table dfsp add column sdk_outbound_url varchar(255) not null default '';
alter table dfsp alter column sdk_outbound_url drop default;

-- the SDK assigns the transfer id; this app's own reference is sent as homeTransactionId
alter table transfers add column home_transaction_id varchar(36);
create index idx_transfers_home_transaction_id on transfers (home_transaction_id);
-- the payee matches the transfer to its quote by quote id now, not by ILP condition
drop index idx_transfers_condition;
