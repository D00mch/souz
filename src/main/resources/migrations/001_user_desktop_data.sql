CREATE VIRTUAL TABLE IF NOT EXISTS user_desktop_data
USING vec0(
    text TEXT NOT NULL,
    embedding FLOAT[1536] distance_metric=cosine
);
