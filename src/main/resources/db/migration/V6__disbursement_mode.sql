-- BULK batches go through the SDK's /bulkTransactions (hub bulk quotes and bulk transfers);
-- INDIVIDUAL batches pay each item as its own transfer. Existing batches were INDIVIDUAL.
alter table disbursements add column mode varchar(16) not null default 'INDIVIDUAL';
alter table disbursements alter column mode drop default;
