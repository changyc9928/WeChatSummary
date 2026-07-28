CREATE TABLE users
(
    id       VARCHAR(36) PRIMARY KEY,
    username VARCHAR(50)  NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL
);