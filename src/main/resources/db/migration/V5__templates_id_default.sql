-- Reconcile schema drift on templates.id.
--
-- On databases where spring.jpa.hibernate.ddl-auto=update created the templates table before V4 ran,
-- the id column exists without the gen_random_uuid() default that V4 declares, because Hibernate
-- generates ids application-side. The seed migration inserts without an explicit id, so the default
-- has to be present. This is a no-op where V4 created the table itself.
ALTER TABLE templates ALTER COLUMN id SET DEFAULT gen_random_uuid();
