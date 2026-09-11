-- 逆向工程流程控制：每个导入任务各阶段的执行状态（持久化，服务重启后可恢复）
CREATE TABLE txt_import_re_steps (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id          BIGINT NOT NULL,
    phase           VARCHAR(40) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    sort_order      INT NOT NULL DEFAULT 0,
    total_units     INT NOT NULL DEFAULT 0,
    completed_units INT NOT NULL DEFAULT 0,
    error_message   VARCHAR(1000),
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (job_id, phase)
);
CREATE INDEX idx_txt_import_re_steps_job ON txt_import_re_steps(job_id);
