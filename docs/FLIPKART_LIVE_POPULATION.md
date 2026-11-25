# Populate Excel from a filtered Flipkart search

Use this workflow when the user authorizes the live result set. For a screenshot-only task, follow the [laptop dataset runbook](LAPTOP_DATASET_RUNBOOK.md).

1. Use the Flipkart tab the user has already filtered in Work mode. Start on page 1 and confirm the query, price limit, sort order and displayed count. Reuse the browser session if already open. Public results normally require no sign-in.
2. Bind the tab as `tab` in the Work browser session. Run the code in [capture-work-browser.js](../scripts/flipkart/capture-work-browser.js), then run `nodeRepl.write(await captureFlipkartPages(tab))`. This follows the site's visible Next links, preserves filter parameters, writes a private JSON capture and restores the initial tab. Use the returned primary path only after capture completes. If pagination fails, inspect the last partial file and the browser state before rerunning.
3. Materialize the user's original, single-sheet comparison workbook. In the hosted spreadsheet runtime, copy `build-live-sheet.mjs` into a temporary directory with `node_modules` linked to `$CODEX_PRIMARY_RUNTIME_NODE_MODULES`. Run the builder with the runtime Node binary. Then append the generated live worksheet package using Python 3.10 or later.

```sh
"$CODEX_PRIMARY_RUNTIME_NODE" "$tmp/build-live-sheet.mjs" "$capture" "$tmp/live.xlsx" "CAPTURE DATE AND TIME ZONE" "VERIFIED FILTERS AND SORT"
python scripts/flipkart/append-live-sheet.py "$baseline" "$tmp/live.xlsx" "$output"
```

4. Check page and row counts against the site's displayed count, report discrepancies, and report distinct product IDs and empty prices. Open the output to check the live tab, the filter and freeze pane, and the original comparison. Save the verified workbook to the user's existing file when authorized, using its current version as the write guard.

## Evidence rules

- Keep one row per observed card in page order, including repeat product IDs. Use the Flipkart PID for joins because a common URL path can serve multiple variants. Use a verified exact SKU before joining to manufacturer specs. Treat OCR or title-only matches as review leads.
- Capture the displayed selling price separately from screenshot evidence, MRP, card discounts, exchange offers and EMI. Keep an empty price if the regular card price cannot be parsed. A truncated title or a missing field calls for verification from the product page.
- The live filter and the comparison budget are independent. The live tab adds current evidence while preserving the original sheet, formulas, cached values, table and screenshot prices. To refresh, use the original one-sheet baseline to produce a new output.
- Keep captures, screenshots, workbook files, tracking links and account details in private storage. Commit reusable code and documentation only.
