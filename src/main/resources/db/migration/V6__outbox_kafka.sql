-- Domain events for Kafka go through the same outbox as the shop's stock updates: written in the transaction of the
-- state change, published later by the relay (ADR 0006).
ALTER TABLE outbox_message DROP CONSTRAINT outbox_message_destination_check;
ALTER TABLE outbox_message ADD CONSTRAINT outbox_message_destination_check
    CHECK (destination IN ('SHOP_STOCK_API', 'KAFKA'));
