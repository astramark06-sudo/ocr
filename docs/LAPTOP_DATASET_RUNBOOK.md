# Laptop research dataset runbook

## Active source boundary

The only authorized laptop evidence for the new analysis is the seven original JPEG screenshots in the replacement archive supplied on 2026-11-25. All preceding laptop screenshots, extracted listings, OCR text, prices, crosswalks, research facts, benchmark joins, review queues, workbooks and databases are superseded. Do not consult, merge, seed or reconcile against them.

The replacement archive stays private outside Git. No product records, OCR counts, prices, benchmarks or completed research results have yet been established for this source set.

## Fresh local processing

1. Extract the replacement archive into a new ignored local source directory. Keep original images unchanged and record SHA-256, size and dimensions for each.
2. Create a new output directory and a new database path; do not reuse or upsert into a previous laptop database.
3. Process each long screenshot in overlapping tiles, retaining original screenshot ID, tile boundaries and pixel coordinates. Check top, bottom and tile joins for coverage.
4. Preserve raw text and confidence separately from normalized listing records. Review clipped and ambiguous cards without inventing values.
5. Record listing prices, MRP, offers, exchange values and EMI separately. Treat screenshot prices as observations, not current market prices.
6. Retain exact SKU evidence and separate candidates from deduplicated products. Do not infer exact identity from family name alone.
7. Reconcile all outputs exclusively against the new source pixels; document coverage, duplicate decisions, unresolved fields and source links.
8. If compatible CSVs are generated, validate using the importer before creating a fresh SQLite database.

```bash
aiops hash-tree ./data/private/laptop-current/source ./data/private/laptop-current/source.manifest.json
aiops verify-manifest ./data/private/laptop-current/source ./data/private/laptop-current/source.manifest.json
python scripts/ingest_research_bundle.py ./data/private/laptop-current/csv --db ./data/private/laptop-current/new.sqlite --dry-run
```

Run the import without `--dry-run` only after checking the report and verifying that the database path is new. The importer and schema remain reusable; any legacy filenames in the importer are compatibility labels, not evidence or expected counts.

Keep private images, datasets, generated files, logs and credentials out of public Git and GitHub Actions. See [research fact schema](RESEARCH_FACT_SCHEMA.md) and [next analysis checklist](NEXT_ANALYSIS_SESSION_CHECKLIST.md).
