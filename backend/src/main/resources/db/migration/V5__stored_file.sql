-- Uploaded images live in the database (DESIGN.md §16): free hosts wipe the server disk on every
-- restart, and one database backup then covers everything. Images are resized on upload (~50-300 KB).
CREATE TABLE stored_file (
  file_key      VARCHAR(200) PRIMARY KEY,               -- e.g. doctors/3f2a….jpg
  content_type  VARCHAR(60)  NOT NULL,
  content       BYTEA        NOT NULL,
  created_at    TIMESTAMP DEFAULT LOCALTIMESTAMP NOT NULL
);
