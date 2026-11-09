-- Empty-database baseline for the current application (MySQL 8.0+).
-- Select the target database explicitly before running. No USE, DROP, seed users,
-- or business data are included. Re-running preserves all existing rows.
-- IF NOT EXISTS is not an upgrade mechanism: use migrations for existing tables.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `user` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    email VARCHAR(100) NOT NULL,
    phone VARCHAR(20) NULL,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(50) NULL,
    avatar VARCHAR(255) NULL,
    gender INT NULL,
    birthday DATE NULL,
    user_type INT NOT NULL DEFAULT 1,
    status INT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    UNIQUE KEY uk_user_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS consultation_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_title VARCHAR(200) NULL,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_emotion_analysis LONGTEXT NULL,
    last_emotion_updated_at DATETIME(6) NULL,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_session_user_deleted_started (user_id, deleted, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS consultation_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    sender_type INT NOT NULL,
    message_type INT NOT NULL DEFAULT 1,
    content LONGTEXT NOT NULL,
    emotion_tag VARCHAR(50) NULL,
    ai_model VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (id),
    KEY ix_message_session_id (session_id, id),
    UNIQUE KEY uk_message_request_sender (request_id, sender_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS knowledge_category (
    id BIGINT NOT NULL AUTO_INCREMENT,
    category_name VARCHAR(100) NOT NULL,
    parent_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS knowledge_article (
    id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(200) NOT NULL,
    category_id BIGINT NULL,
    summary TEXT NULL,
    content LONGTEXT NOT NULL,
    cover_image VARCHAR(255) NULL,
    tags VARCHAR(255) NULL,
    author_id BIGINT NULL,
    status INT NOT NULL DEFAULT 0,
    read_count INT NOT NULL DEFAULT 0,
    published_at DATETIME(6) NULL,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_article_visible_published (deleted, status, published_at),
    KEY ix_article_category (category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS emotion_diary (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    diary_date DATE NOT NULL,
    mood_score INT NOT NULL,
    dominant_emotion VARCHAR(20) NOT NULL,
    emotion_triggers TEXT NULL,
    diary_content TEXT NULL,
    sleep_quality INT NULL,
    stress_level INT NULL,
    ai_emotion_analysis LONGTEXT NULL,
    ai_analysis_updated_at DATETIME(6) NULL,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_diary_user_deleted_date (user_id, deleted, diary_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ai_request_record (
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    scene VARCHAR(32) NOT NULL,
    client_request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    user_message_id BIGINT NULL,
    ai_message_id BIGINT NULL,
    ai_model VARCHAR(100) NULL,
    dispatched TINYINT(1) NOT NULL DEFAULT 0,
    error_code VARCHAR(8) NULL,
    error_message VARCHAR(255) NULL,
    saved TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    expires_at DATETIME(6) NULL,
    PRIMARY KEY (request_id),
    UNIQUE KEY uk_request_user_scene_client (user_id, scene, client_request_id),
    KEY ix_request_status (status),
    KEY ix_request_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
