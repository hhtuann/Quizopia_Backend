# Schema Equivalence Report (Consolidation V1–V15 → V1–V9)

## Method

Both the pre-consolidation migration set (V1–V15) and the consolidated set (V1–V9) were applied
to a fresh disposable PostgreSQL 17 database. Each resulting schema was dumped with
`pg_dump --schema-only --no-owner --no-privileges`, then compared object-by-object (not by raw
text diff, which is noisy).

Full dumps are kept locally outside Git. This file records only the conclusion.

## Result

**Equivalent.** No disallowed differences. Both dumps are 2590 lines.

| Object class | Compared | Equivalent |
|---|---|---|
| Schemas | names | yes |
| Tables | name, columns, types, nullability, defaults, identity | yes |
| Primary keys | columns | yes |
| Foreign keys | columns, referenced table, ON DELETE behaviour | yes |
| Unique constraints | columns, partial predicates | yes |
| Check constraints | expression | yes |
| Indexes | name, columns, uniqueness, predicate | yes |
| Sequences | name, type | yes |

## Allowed differences (expected from a history rewrite)

- `flyway_schema_history` content (installed migrations, descriptions, checksums).
- Migration file checksums and descriptions.

These are exactly the metadata a history rewrite changes; none affect the live schema.

## Disallowed differences (none found)

No missing/extra table, column, type mismatch, nullability change, default change, missing FK/UQ/
CHECK, or missing index. Hibernate `validate` passes against the consolidated schema
(`spring.jpa.hibernate.ddl-auto=validate`), which independently confirms every entity maps to a
matching table/column/constraint.

## CHECK-semantic notes

Two CHECK constraints were carried into the final schema from absorbed patch migrations — these
express the genuine final semantics, not consolidation artifacts:

- `exam_sessions.max_attempts >= 0` (0 means unlimited). Covered by
  `V8ExamSchemaIntegrationTests.sessionMaxAttemptsZeroAccepted` /
  `sessionMaxAttemptsNegativeRejected`.
- `question_versions.metadata` NUMERIC_FILL shape (simplified).
