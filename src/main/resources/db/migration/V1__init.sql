CREATE TABLE users (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    email       VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE events (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    venue       VARCHAR(200) NOT NULL,
    starts_at   TIMESTAMPTZ  NOT NULL
);

CREATE TABLE seats (
    id          BIGSERIAL PRIMARY KEY,
    event_id    BIGINT       NOT NULL REFERENCES events(id),
    seat_label  VARCHAR(20)  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_seat_per_event UNIQUE (event_id, seat_label)
);

CREATE TABLE bookings (
    id               BIGSERIAL PRIMARY KEY,
    seat_id          BIGINT       NOT NULL REFERENCES seats(id),
    user_id          BIGINT       NOT NULL REFERENCES users(id),
    status           VARCHAR(20)  NOT NULL
                     CHECK (status IN ('HELD', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    hold_expires_at  TIMESTAMPTZ,
    idempotency_key  VARCHAR(100) UNIQUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- A seat can have at most ONE active booking, whatever the Java code does.
CREATE UNIQUE INDEX uq_one_active_booking_per_seat
    ON bookings (seat_id)
    WHERE status IN ('HELD', 'CONFIRMED');
