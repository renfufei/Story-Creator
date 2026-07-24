CREATE TABLE side_stories (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id        BIGINT NOT NULL,
    title             VARCHAR(200) NOT NULL,
    description       TEXT,
    type              VARCHAR(30) NOT NULL DEFAULT 'SUPPLEMENTARY',
    status            VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    attached_volume   INT,
    sort_order        INT NOT NULL DEFAULT 0,
    outline           TEXT,
    creative_guidance TEXT,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_side_stories_project ON side_stories(project_id);

CREATE TABLE side_story_characters (
    side_story_id BIGINT NOT NULL,
    character_id  BIGINT NOT NULL,
    PRIMARY KEY (side_story_id, character_id)
);

CREATE TABLE side_story_chapters (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    side_story_id    BIGINT NOT NULL,
    project_id       BIGINT NOT NULL,
    chapter_number   INT NOT NULL,
    title            VARCHAR(200),
    outline_summary  TEXT,
    content          TEXT,
    word_count       INT NOT NULL DEFAULT 0,
    status           VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED',
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (side_story_id, chapter_number)
);
CREATE INDEX idx_ssc_side_story ON side_story_chapters(side_story_id);
CREATE INDEX idx_ssc_project ON side_story_chapters(project_id);
