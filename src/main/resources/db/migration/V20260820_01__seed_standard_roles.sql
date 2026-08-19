-- Seed standard CRM roles (idempotent). RoleBootstrap also applies this at startup.

INSERT INTO roles (name, description)
SELECT 'ADMIN', 'Administrator with full access. Can create, update, delete any data and manage all users.'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'ADMIN');

INSERT INTO roles (name, description)
SELECT 'CATEGORY_MANAGER', 'Category manager. Oversees sales and presales teams within their category.'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'CATEGORY_MANAGER');

INSERT INTO roles (name, description)
SELECT 'SALES', 'Sales user. Manages deals, organizations, pipelines, and presales team members.'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'SALES');

INSERT INTO roles (name, description)
SELECT 'PRESALES', 'Presales user. Supports sales with leads, activities, and deals under a sales manager.'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'PRESALES');
