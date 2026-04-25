-- V2__seed_symbols.sql
-- Seed the five tradable products listed in spec §5.1.

INSERT INTO symbols (symbol, name, ref_price) VALUES
    ('AAPL',  'Apple Inc.',       180.0000),
    ('MSFT',  'Microsoft Corp.',  420.0000),
    ('GOOGL', 'Alphabet Inc.',    155.0000),
    ('TSLA',  'Tesla Inc.',       240.0000),
    ('AMZN',  'Amazon.com Inc.',  190.0000);
