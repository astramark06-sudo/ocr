# Laptop research bundle ingestion

The [import guide](LAPTOP_DATA_IMPORT.md) and [dataset runbook](LAPTOP_DATASET_RUNBOOK.md) define the current local workflow. Generate input CSVs exclusively from the new authorized screenshot archive. The reusable importer performs validation before database writes and stores each row's full payload. Missing optional files are reported as missing, not as zero-row evidence.

Keep candidate observations, listing records, configuration facts, benchmarks, sources, crosswalk decisions and price evidence separate. Crosswalk links are review aids, not exact SKU proof. The importer accepts legacy-named files for compatibility; their names do not authorize the use of old data.

Always use a fresh output database for the replacement source set. Do not commit private source or output files.
