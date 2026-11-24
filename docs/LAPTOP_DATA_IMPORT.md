# Laptop research bundle import

Use only freshly generated CSV exports derived from the current authorized private source archive. Previous extracts, databases, product lists and benchmark joins are not inputs.

The reusable importer stores complete rows as JSON in SQLite; stable keys are upserted and keyless tables use deterministic row-content hashes. See the current input contracts in [the importer](../scripts/ingest_research_bundle.py). Legacy filename suffixes are compatibility identifiers, not expected record counts.

```bash
python scripts/ingest_research_bundle.py ./data/private/laptop-current/csv --db ./data/private/laptop-current/new.sqlite --dry-run
```

Review missing files, headers, keys, duplicate rows and the reported counts before running without `--dry-run`. Use a new database for the replacement evidence. Do not mix source generations or reuse a previous database. Record actual counts only after validation.

See [the dataset runbook](LAPTOP_DATASET_RUNBOOK.md) and [research fact schema](RESEARCH_FACT_SCHEMA.md). Keep all source screenshots and derived data private.
