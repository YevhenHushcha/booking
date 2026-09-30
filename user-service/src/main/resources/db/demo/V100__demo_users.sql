-- Demo accounts for local runs; both passwords are "password" (BCrypt, cost 10).
-- This location is enabled in application.yml only; a production deployment would not ship it.
INSERT INTO users (email, password_hash, roles) VALUES
    ('alice@coworking.test', '$2a$10$/mhHtmEfKqJfdpeAZb2MlecNwfvvPfVqoV/k/VC9XOnTse19k2Zy.', 'USER'),
    ('bob@coworking.test',   '$2a$10$/mhHtmEfKqJfdpeAZb2MlecNwfvvPfVqoV/k/VC9XOnTse19k2Zy.', 'USER,ADMIN');
