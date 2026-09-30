CREATE TABLE workspaces (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name     VARCHAR(255) NOT NULL,
    address  VARCHAR(255) NOT NULL,
    capacity INT          NOT NULL CHECK (capacity > 0)
);
