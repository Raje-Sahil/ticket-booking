TRUNCATE bookings, seats, events, users RESTART IDENTITY CASCADE;

INSERT INTO users (name, email) VALUES
    ('Alice', 'alice@example.com'),
    ('Bob', 'bob@example.com');

INSERT INTO events (name, venue, starts_at)
VALUES ('Demo Concert', 'Main Hall', now() + interval '30 days');

INSERT INTO seats (event_id, seat_label)
SELECT e.id, 'A' || g
FROM events e, generate_series(1, 10) AS g;
