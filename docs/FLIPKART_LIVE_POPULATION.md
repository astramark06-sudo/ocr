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

## Verify exact models on product pages

1. Start from the current workbook and unresolved listing IDs. Use the captured product links and group repeat observations by PID to avoid opening the same product twice. Prioritize the shortlist and compare CPU, RAM, storage, and model family before mapping a retailer PID to a screenshot row. Leave ambiguous matches open.
2. In the connected Work browser, open the existing product link in a separate tab and verify the resulting PID and selected configuration. Wait for the Specifications section and, where present, the Model Number and Part Number rows. Flipkart can render these after the page title and highlights appear. An early empty snapshot is insufficient to mark a field unavailable.
3. Read label/value pairs from the visible Specifications section. Collect only relevant fields, such as Model Number, Part Number, Processor Variant, RAM, RAM Type, SSD Capacity, Screen Size, Screen Resolution, Battery Cell, and Weight. Keep the source URL with PID, observation date, and matched workbook listing IDs in the private handoff. Exclude account, delivery-location, and cart data.
4. Record the retailer model number and part number as separate fields. When one PID lists multiple part numbers, retain them as alternatives and leave the exact delivered part unresolved. Keep the original screenshot price separate from any product-page selling price, fee, or offer. Use an exact part number for manufacturer-spec joins.
5. Checkpoint verified evidence and unresolved IDs before ending the session. Record whether the findings have been written to Excel. Prioritize remaining blanks and ambiguous matches on resume, without reopening pages whose evidence still applies.

In the shortlist trial, six distinct product pages yielded five retailer part-number fields. One of those fields listed two possible part numbers, and the sixth page remained unresolved. The result-card import alone could not establish those fields.

Use Work mode for the connected browser session and saved workbook writeback. Pass a compact evidence table and the unresolved queue to normal ChatGPT for comparison and recommendations. Keep per-listing results and workbook files outside this public repository.

## Resume a saved research checkpoint

Run the read-only queue builder against the latest private checkpoint, not an older capture:

```sh
node scripts/flipkart/research-queue.mjs /private/checkpoint.json /private/research-queue.json 55000
````

The output separates manufacturer-reference candidates, rows needing model/part identification, out-of-budget rows and unknown-price rows. The browser queue groups by PID, retains all affected listing IDs and reasons, prioritizes reviewed conflicts, and strips tracking parameters from valid captured Flipkart links. An empty URL list means the PID needs a manually verified link; never fabricate one. A candidate is not a confirmed model assignment. The builder does not change the source checkpoint or Excel workbook, does not make network calls, and refuses to overwrite the output. Keep both input and output private. Run this again only when a new verified checkpoint exists or the budget changes.
