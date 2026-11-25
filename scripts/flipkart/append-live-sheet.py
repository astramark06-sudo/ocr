import sys
from copy import deepcopy
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED

if len(sys.argv) != 4:
    raise SystemExit("Usage: append-live-sheet.py BASE.xlsx LIVE.xlsx OUTPUT.xlsx")
base, live, output = map(Path, sys.argv[1:])
if output.resolve() in {base.resolve(), live.resolve()} or output.exists():
    raise SystemExit("Choose a new output path to keep both input files intact")
ns = '{http://schemas.openxmlformats.org/spreadsheetml/2006/main}'
sheet_path = 'xl/worksheets/sheet1.xml'
with ZipFile(base) as original, ZipFile(live) as source:
    sheet = ET.fromstring(source.read(sheet_path))
    strings = list(ET.fromstring(source.read('xl/sharedStrings.xml')))
    base_book = ET.fromstring(original.read('xl/workbook.xml'))
    if len(base_book.find(ns+'sheets')) != 1 or 'xl/worksheets/sheet2.xml' in original.namelist():
        raise SystemExit("Use the original one-sheet comparison workbook as the baseline")
    orig_sheet = ET.fromstring(original.read(sheet_path))
    header_style = orig_sheet.find(f'.//{ns}c[@r="A4"]')
    header_style = header_style.get('s') if header_style is not None else None
    headers = {f'{letter}4' for letter in 'ABCDEFGHIJKL'}
    for cell in sheet.iter(ns+'c'):
        cell.attrib.pop('s', None)
        if cell.get('r') in headers and header_style:
            cell.set('s', header_style)
        if cell.get('t') == 's':
            value = cell.find(ns+'v')
            item = strings[int(value.text)]
            cell.remove(value)
            cell.set('t','inlineStr')
            inline = ET.SubElement(cell,ns+'is')
            inline.extend(deepcopy(child) for child in item)
    for col in sheet.iter(ns+'col'):
        col.attrib.pop('style', None)
    for row in sheet.iter(ns+'row'):
        row.attrib.pop('s', None)
    views = sheet.find(ns+'sheetViews')
    if views is None:
        views = ET.Element(ns+'sheetViews')
        sheet.insert(1 if sheet.find(ns+'dimension') is not None else 0,views)
    views.clear()
    view = ET.SubElement(views,ns+'sheetView',workbookViewId='0')
    ET.SubElement(view,ns+'pane',ySplit='4',topLeftCell='A5',activePane='bottomLeft',state='frozen')
    data = sheet.find(ns+'sheetData')
    rows = data.findall(ns+'row')
    last_row = max(int(row.get('r')) for row in rows)
    listing_rows = [row for row in rows if int(row.get('r')) >= 5]
    if last_row != len(listing_rows) + 4 or not listing_rows:
        raise ValueError("Live worksheet has missing or noncontiguous listing rows")
    sheet.insert(list(sheet).index(data)+1, ET.Element(ns+'autoFilter', ref=f'A4:L{last_row}'))
    rtype = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
    replacements = {
        'xl/workbook.xml': ('</x:sheets>',f'<x:sheet name="Flipkart Live Results" sheetId="2" xmlns:r="{rtype}" r:id="RFlipkartLive"/></x:sheets>'),
        'xl/_rels/workbook.xml.rels': ('</Relationships>',f'<Relationship Type="{rtype}/worksheet" Target="/xl/worksheets/sheet2.xml" Id="RFlipkartLive"/></Relationships>'),
        '[Content_Types].xml': ('</Types>','<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>')
    }
    with ZipFile(output,'w',ZIP_DEFLATED) as target:
        for part in original.namelist():
            content = original.read(part)
            if part in replacements:
                before,after = replacements[part]
                assert content.count(before.encode()) == 1
                content = content.replace(before.encode(),after.encode())
            target.writestr(part,content)
        target.writestr('xl/worksheets/sheet2.xml', ET.tostring(sheet, encoding='utf-8',xml_declaration=True))
    with ZipFile(output) as target:
        assert target.testzip() is None
        for part in original.namelist():
            if part not in replacements:
                assert target.read(part) == original.read(part), part

print(f"Verified {len(listing_rows)} listing rows and preserved original worksheet parts: {output.resolve()}")
