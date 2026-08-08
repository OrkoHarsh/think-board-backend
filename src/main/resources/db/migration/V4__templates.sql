-- NimbusBoard schema V4
-- Board templates: reusable starter layouts applied at board creation time.
--
-- Written defensively: this schema previously ran with Flyway disabled and
-- spring.jpa.hibernate.ddl-auto=update, so Hibernate may already have created the templates table
-- and boards.template_slug from the entity mappings before this migration first runs.
CREATE TABLE IF NOT EXISTS templates (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug        VARCHAR(100) NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    description VARCHAR(500),
    category    VARCHAR(50)  NOT NULL,
    definition  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    sort_order  INT          NOT NULL DEFAULT 0,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The seed migration upserts with ON CONFLICT (slug), which requires a unique constraint on slug.
-- If the table came from Hibernate rather than the statement above, that constraint may be absent.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE t.relname = 'templates'
          AND c.contype = 'u'
          AND pg_get_constraintdef(c.oid) LIKE '%(slug)%'
    ) THEN
        ALTER TABLE templates ADD CONSTRAINT templates_slug_key UNIQUE (slug);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_templates_active ON templates(is_active, sort_order);

-- Which template a board was created from (nullable: blank boards have none).
ALTER TABLE boards ADD COLUMN IF NOT EXISTS template_slug VARCHAR(100);
