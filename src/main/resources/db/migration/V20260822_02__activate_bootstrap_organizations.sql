-- Mark organizations active when they already have bootstrap vendor creds + default pipeline.
UPDATE organizations o
SET o.is_active = 1
WHERE IFNULL(o.is_active, 0) = 0
  AND EXISTS (
    SELECT 1
    FROM brideside_vendors bv
    INNER JOIN pipelines p ON p.id = bv.pipeline_id
    WHERE bv.organization_id = o.id
      AND bv.username IS NOT NULL
      AND TRIM(bv.username) <> ''
      AND IFNULL(p.is_deleted, 0) = 0
  );
