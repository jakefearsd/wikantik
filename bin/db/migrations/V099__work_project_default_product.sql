-- Work: a project may name a default product for the stories and tasks filed in it (DDL only).
ALTER TABLE work_project
    ADD COLUMN IF NOT EXISTS default_product_id BIGINT REFERENCES work_product (id);
