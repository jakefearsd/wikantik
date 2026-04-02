#!/usr/bin/env python3
"""Bulk-convert JSPWiki .txt files to Markdown .md files.

Scans a directory for .txt files, checks each for wiki syntax using the same
heuristic as WikiToMarkdownConverter.java, converts wiki syntax to Markdown,
writes a .md file, and deletes the original .txt file.

Files that are already Markdown (or score below the heuristic threshold) are
skipped.  Files that already have a corresponding .md file are also skipped.

Usage:
    python wiki2markdown.py <directory>
    python wiki2markdown.py <directory> --dry-run   # preview only
"""

import argparse
import os
import re
import sys
from pathlib import Path


# ---------------------------------------------------------------------------
# Heuristic detection — mirrors WikiToMarkdownConverter.isLikelyWikiSyntax()
# ---------------------------------------------------------------------------

HEURISTIC_THRESHOLD = 3

_WIKI_HEADING = re.compile(r'^!{1,3}\s', re.MULTILINE)
_WIKI_ITALIC = re.compile(r"''[^']+?''")
_WIKI_PIPE_LINK = re.compile(r'\[([^\]|]+)\|([^\]]+)\]')
_WIKI_PLUGIN_UNCONVERTED = re.compile(r'\[\{[^}]+\}\](?!\()')
_WIKI_CODE_BLOCK_DELIM = re.compile(r'\{\{\{')
_WIKI_BOLD = re.compile(r'__[^_]+?__')


def is_likely_wiki_syntax(content: str) -> bool:
    """Return True if *content* scores above the wiki-syntax heuristic threshold."""
    if not content or not content.strip():
        return False
    score = 0
    if _WIKI_HEADING.search(content):            score += 2
    if _WIKI_ITALIC.search(content):             score += 2
    if _WIKI_PIPE_LINK.search(content):          score += 2
    if _WIKI_PLUGIN_UNCONVERTED.search(content): score += 2
    if _WIKI_CODE_BLOCK_DELIM.search(content):   score += 1
    if _WIKI_BOLD.search(content):               score += 1
    return score >= HEURISTIC_THRESHOLD


# ---------------------------------------------------------------------------
# Conversion — mirrors WikiToMarkdownConverter.convert() / convertInline()
# ---------------------------------------------------------------------------

_HEADING3 = re.compile(r'^!!!\s*(.*)')
_HEADING2 = re.compile(r'^!!\s*(.*)')
_HEADING1 = re.compile(r'^!\s*(.*)')
_HR = re.compile(r'^-{4,}\s*$')
_UNORDERED_LIST = re.compile(r'^(\*{1,})\s+(.*)')
_ORDERED_LIST = re.compile(r'^(#{1,})\s+(.*)')
_TABLE_HEADER = re.compile(r'^\|\|(.*)$')
_TABLE_ROW = re.compile(r'^\|(.*)$')
_PLUGIN_SYNTAX = re.compile(r'\[\{([^}]*)\}\]')
_WIKI_LINK_WITH_TEXT = re.compile(r'\[([^\]|]+)\|([^\]]+)\]')
_WIKI_BARE_LINK = re.compile(r'\[([^\]\[|]+?)\](?!\()')
_BOLD_SYNTAX = re.compile(r'__([^_]+?)__')
_ITALIC_SYNTAX = re.compile(r"''([^']+?)''")
_INLINE_CODE = re.compile(r'\{\{([^}]+?)\}\}')
_LINE_BREAK = re.compile(r'\\\\')
_DEFINITION_LIST = re.compile(r'^;([^:]*):(.*)$')

_ESC_BRACKET = '\x00ESC_BRACKET\x00'


def _convert_inline(line: str) -> str:
    """Apply inline wiki→Markdown conversions to a single line."""
    result = line

    # 0. Escape sequences: [[ → placeholder
    result = result.replace('[[', _ESC_BRACKET)

    # 1. Plugin / ACL / variable: [{…}] → [{…}]()
    result = _PLUGIN_SYNTAX.sub(r'[{\1}]()', result)

    # 2. Wiki links with text: [text|url] → [text](url)
    result = _WIKI_LINK_WITH_TEXT.sub(r'[\1](\2)', result)

    # 3. Bare wiki links: [PageName] → [PageName]()
    result = _WIKI_BARE_LINK.sub(r'[\1]()', result)

    # 4. Bold: __text__ → **text**
    result = _BOLD_SYNTAX.sub(r'**\1**', result)

    # 5. Italic: ''text'' → *text*
    result = _ITALIC_SYNTAX.sub(r'*\1*', result)

    # 6. Inline code: {{text}} → `text`
    result = _INLINE_CODE.sub(r'`\1`', result)

    # 7. Line breaks: \\ → two trailing spaces
    result = _LINE_BREAK.sub('  ', result)

    # 8. Restore escaped brackets
    result = result.replace(_ESC_BRACKET, '[')

    return result


def _split_table_cells(row_content: str, is_header: bool) -> list[str]:
    """Split a wiki table row into individual cell values."""
    content = row_content
    if is_header and content.endswith('||'):
        content = content[:-2]
    elif not is_header and content.endswith('|'):
        content = content[:-1]

    sep = '||' if is_header else '|'
    cells = content.split(sep)
    return [_convert_inline(c.strip()) for c in cells]


def _flush_table(rows: list[list[str]], has_header: bool) -> str:
    """Render accumulated table rows as a Markdown table."""
    if not rows:
        return ''

    max_cols = max(len(r) for r in rows)
    lines: list[str] = []

    if has_header:
        lines.append(_table_row(rows[0], max_cols))
        lines.append('|' + ' --- |' * max_cols)
        for row in rows[1:]:
            lines.append(_table_row(row, max_cols))
    else:
        lines.append('|' + '   |' * max_cols)
        lines.append('|' + ' --- |' * max_cols)
        for row in rows:
            lines.append(_table_row(row, max_cols))

    return '\n'.join(lines) + '\n'


def _table_row(cells: list[str], max_cols: int) -> str:
    parts = []
    for c in range(max_cols):
        parts.append(cells[c] if c < len(cells) else '')
    return '| ' + ' | '.join(parts) + ' | '


def convert(wiki_text: str) -> tuple[str, list[str]]:
    """Convert JSPWiki syntax to Markdown.

    Returns ``(markdown, warnings)`` mirroring the Java ``ConversionResult``.
    """
    if not wiki_text:
        return ('', [])

    warnings: list[str] = []
    result: list[str] = []
    table_buffer: list[list[str]] = []
    has_header_row = False

    NORMAL, CODE_BLOCK = 'normal', 'code_block'
    state = NORMAL

    lines = wiki_text.split('\n')
    # Preserve trailing empty segments the same way Java split("\n", -1) does
    if wiki_text.endswith('\n'):
        lines.append('')

    for line in lines:
        # --- Code block handling ---
        if state == CODE_BLOCK:
            if line.strip() == '}}}':
                result.append('```')
                state = NORMAL
            else:
                result.append(line)
            continue

        if line.strip().startswith('{{{'):
            if table_buffer:
                result.append(_flush_table(table_buffer, has_header_row).rstrip('\n'))
                table_buffer.clear()
                has_header_row = False
            result.append('```')
            after_open = line.strip()[3:].strip()
            if after_open:
                result.append(after_open)
            state = CODE_BLOCK
            continue

        # --- Table row handling ---
        header_m = _TABLE_HEADER.match(line)
        row_m = _TABLE_ROW.match(line)

        if header_m:
            cells = _split_table_cells(header_m.group(1), True)
            table_buffer.append(cells)
            has_header_row = True
            continue
        elif table_buffer and row_m:
            cells = _split_table_cells(row_m.group(1), False)
            table_buffer.append(cells)
            continue

        # Flush pending table on non-table line
        if table_buffer:
            result.append(_flush_table(table_buffer, has_header_row).rstrip('\n'))
            table_buffer.clear()
            has_header_row = False

        # Start a new headerless table
        if row_m and line.startswith('|') and not line.startswith('||'):
            cells = _split_table_cells(row_m.group(1), False)
            table_buffer.append(cells)
            continue

        # --- Line-by-line conversions ---
        converted = line

        # Headings (longest prefix first)
        m = _HEADING3.match(converted)
        if m:
            result.append('### ' + _convert_inline(m.group(1).strip()))
            continue
        m = _HEADING2.match(converted)
        if m:
            result.append('## ' + _convert_inline(m.group(1).strip()))
            continue
        m = _HEADING1.match(converted)
        if m:
            result.append('# ' + _convert_inline(m.group(1).strip()))
            continue

        # Horizontal rule
        if _HR.match(converted):
            result.append('---')
            continue

        # Definition list
        m = _DEFINITION_LIST.match(converted)
        if m:
            term = m.group(1).strip()
            definition = m.group(2).strip()
            if not term:
                result.append(': ' + _convert_inline(definition))
            else:
                result.append('**' + _convert_inline(term) + '**: ' + _convert_inline(definition))
            continue

        # Unordered list
        m = _UNORDERED_LIST.match(converted)
        if m:
            depth = len(m.group(1))
            indent = '  ' * (depth - 1)
            result.append(indent + '* ' + _convert_inline(m.group(2)))
            continue

        # Ordered list
        m = _ORDERED_LIST.match(converted)
        if m:
            depth = len(m.group(1))
            indent = '   ' * (depth - 1)
            result.append(indent + '1. ' + _convert_inline(m.group(2)))
            continue

        # Normal line — apply inline conversions
        result.append(_convert_inline(converted))

    # Flush remaining table
    if table_buffer:
        result.append(_flush_table(table_buffer, has_header_row).rstrip('\n'))

    # Flush unclosed code block
    if state == CODE_BLOCK:
        result.append('```')
        warnings.append('Unclosed code block ({{{ without matching }}}) was auto-closed')

    markdown = '\n'.join(result)

    # Remove trailing newline to match input convention
    if markdown.endswith('\n') and not wiki_text.endswith('\n'):
        markdown = markdown[:-1]

    return (markdown, warnings)


# ---------------------------------------------------------------------------
# Bulk file operations
# ---------------------------------------------------------------------------

def process_directory(directory: str, *, dry_run: bool = False) -> dict:
    """Scan *directory* for .txt files and convert wiki-syntax ones to .md.

    Returns a summary dict with counts of converted / skipped / error files.
    """
    dir_path = Path(directory)
    if not dir_path.is_dir():
        print(f'Error: {directory} is not a directory', file=sys.stderr)
        sys.exit(1)

    stats = {'converted': 0, 'renamed': 0, 'skipped_md_exists': 0, 'errors': 0}

    txt_files = sorted(dir_path.glob('*.txt'))
    if not txt_files:
        print(f'No .txt files found in {directory}')
        return stats

    for txt_path in txt_files:
        md_path = txt_path.with_suffix('.md')
        stem = txt_path.stem

        # Skip if .md already exists
        if md_path.exists():
            print(f'  SKIP  {txt_path.name}  (.md already exists)')
            stats['skipped_md_exists'] += 1
            continue

        try:
            content = txt_path.read_text(encoding='utf-8')
        except Exception as e:
            print(f'  ERROR {txt_path.name}  (read failed: {e})', file=sys.stderr)
            stats['errors'] += 1
            continue

        if not is_likely_wiki_syntax(content):
            # Already markdown — rename .txt → .md without conversion
            if dry_run:
                print(f'  WOULD rename {txt_path.name} → {md_path.name}  (already markdown)')
            else:
                try:
                    txt_path.rename(md_path)
                    print(f'  RENAME {txt_path.name} → {md_path.name}  (already markdown)')
                except Exception as e:
                    print(f'  ERROR {txt_path.name}  (rename failed: {e})', file=sys.stderr)
                    stats['errors'] += 1
                    continue
            stats['renamed'] += 1
            continue

        markdown, warnings = convert(content)

        if dry_run:
            print(f'  WOULD {txt_path.name} → {md_path.name}')
            for w in warnings:
                print(f'         ⚠ {w}')
        else:
            try:
                md_path.write_text(markdown, encoding='utf-8')
                txt_path.unlink()
                print(f'  DONE  {txt_path.name} → {md_path.name}')
                for w in warnings:
                    print(f'         ⚠ {w}')
            except Exception as e:
                print(f'  ERROR {txt_path.name}  (write failed: {e})', file=sys.stderr)
                stats['errors'] += 1
                continue

        stats['converted'] += 1

    return stats


def main():
    parser = argparse.ArgumentParser(
        description='Bulk-convert JSPWiki .txt files to Markdown .md files.')
    parser.add_argument('directory', help='Directory containing .txt files to convert')
    parser.add_argument('--dry-run', action='store_true',
                        help='Preview conversions without writing files')
    args = parser.parse_args()

    print(f'Scanning {args.directory} ...')
    stats = process_directory(args.directory, dry_run=args.dry_run)

    print(f'\nSummary: {stats["converted"]} converted, '
          f'{stats["renamed"]} renamed (already markdown), '
          f'{stats["skipped_md_exists"]} skipped (.md exists), '
          f'{stats["errors"]} errors')


if __name__ == '__main__':
    main()
