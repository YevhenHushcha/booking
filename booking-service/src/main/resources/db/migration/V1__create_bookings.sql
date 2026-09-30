CREATE TABLE bookings (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Ids from other services' databases: no foreign keys across the service boundary.
    user_id      BIGINT                   NOT NULL,
    workspace_id BIGINT                   NOT NULL,
    starts_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at)
);

CREATE INDEX bookings_user_id_idx ON bookings (user_id);
