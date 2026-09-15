-- Clear owner_id values that point to deleted users (common after DB reset / re-seed).
UPDATE persons p
LEFT JOIN users u ON p.owner_id = u.id
SET p.owner_id = NULL
WHERE p.owner_id IS NOT NULL AND u.id IS NULL;

UPDATE deals d
LEFT JOIN users u ON d.owner_id = u.id
SET d.owner_id = NULL
WHERE d.owner_id IS NOT NULL AND u.id IS NULL;

UPDATE organizations o
LEFT JOIN users u ON o.owner_id = u.id
SET o.owner_id = NULL
WHERE o.owner_id IS NOT NULL AND u.id IS NULL;
