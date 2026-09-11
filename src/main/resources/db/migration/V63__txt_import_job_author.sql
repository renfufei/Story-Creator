-- TXT 导入任务表新增作者字段
ALTER TABLE txt_import_jobs ADD COLUMN author VARCHAR(200);
