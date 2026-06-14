-- Add chapter word count config to projects
ALTER TABLE projects ADD COLUMN chapter_word_count INT NOT NULL DEFAULT 5000;
ALTER TABLE projects ADD COLUMN chapter_word_count_min INT NOT NULL DEFAULT 4000;
ALTER TABLE projects ADD COLUMN chapter_word_count_max INT NOT NULL DEFAULT 6000;
