"""Tests for wiki2markdown.py — mirrors WikiToMarkdownConverterTest.java."""

import os
import tempfile
from pathlib import Path

import pytest

from wiki2markdown import convert, is_likely_wiki_syntax, process_directory


# ==================== Headings ====================

@pytest.mark.parametrize('wiki,expected', [
    ('!!! Large heading', '# Large heading'),
    ('!! Medium heading', '## Medium heading'),
    ('! Small heading', '### Small heading'),
])
def test_headings(wiki, expected):
    assert convert(wiki)[0] == expected


def test_heading_with_inline_formatting():
    assert convert('! __Bold__ heading')[0] == '### **Bold** heading'


# ==================== Text Formatting ====================

def test_bold():
    assert convert('This is __bold__ text.')[0] == 'This is **bold** text.'


def test_italic():
    assert convert("This is ''italic'' text.")[0] == 'This is *italic* text.'


def test_inline_code():
    assert convert('Use {{System.out}} for output.')[0] == 'Use `System.out` for output.'


def test_combined_formatting():
    assert convert("__bold__ and ''italic'' and {{code}}")[0] == '**bold** and *italic* and `code`'


# ==================== Links ====================

def test_wiki_link_with_text():
    assert convert('[Click here|MyPage]')[0] == '[Click here](MyPage)'


def test_wiki_link_with_url():
    assert convert('[Google|http://google.com]')[0] == '[Google](http://google.com)'


def test_bare_wiki_link():
    assert convert('[MyPage]')[0] == '[MyPage]()'


def test_escaped_brackets():
    assert convert('[[Literal]')[0] == '[Literal]'


# ==================== Lists ====================

def test_unordered_list():
    assert convert('* Item one')[0] == '* Item one'


def test_nested_unordered_list():
    wiki = '* Level 1\n** Level 2\n*** Level 3'
    expected = '* Level 1\n  * Level 2\n    * Level 3'
    assert convert(wiki)[0] == expected


def test_ordered_list():
    assert convert('# First item')[0] == '1. First item'


def test_nested_ordered_list():
    wiki = '# Level 1\n## Level 2\n### Level 3'
    expected = '1. Level 1\n   1. Level 2\n      1. Level 3'
    assert convert(wiki)[0] == expected


def test_list_with_inline_formatting():
    assert convert("* __Bold__ item with ''italic''")[0] == '* **Bold** item with *italic*'


# ==================== Tables ====================

def test_simple_table():
    wiki = '|| Header 1 || Header 2\n| Cell 1 | Cell 2'
    md, _ = convert(wiki)
    assert 'Header 1' in md
    assert 'Cell 1' in md
    assert '---' in md


def test_table_with_trailing_separators():
    wiki = '|| H1 || H2 ||\n| C1 | C2 |'
    md, _ = convert(wiki)
    assert 'H1' in md
    assert 'C1' in md


# ==================== Code Blocks ====================

def test_code_block():
    wiki = '{{{\nint x = 42;\nreturn x;\n}}}'
    expected = '```\nint x = 42;\nreturn x;\n```'
    assert convert(wiki)[0] == expected


def test_code_block_preserves_content():
    wiki = '{{{\n!!!Not a heading\n__Not bold__\n}}}'
    expected = '```\n!!!Not a heading\n__Not bold__\n```'
    assert convert(wiki)[0] == expected


def test_unclosed_code_block():
    wiki = '{{{\nunclosed code'
    md, warnings = convert(wiki)
    assert '```' in md
    assert any('Unclosed code block' in w for w in warnings)


# ==================== Horizontal Rules ====================

def test_horizontal_rule():
    assert convert('----')[0] == '---'


def test_long_horizontal_rule():
    assert convert('----------')[0] == '---'


# ==================== Line Breaks ====================

def test_line_break():
    assert convert('Line one\\\\and more')[0] == 'Line one  and more'


# ==================== Plugins / ACL / Variables ====================

def test_plugin_syntax():
    assert convert('[{TableOfContents}]')[0] == '[{TableOfContents}]()'


def test_plugin_with_params():
    assert convert("[{INSERT CurrentTimePlugin format='yyyy-MM-dd'}]")[0] == \
        "[{INSERT CurrentTimePlugin format='yyyy-MM-dd'}]()"


def test_acl_syntax():
    assert convert('[{ALLOW view Admin}]')[0] == '[{ALLOW view Admin}]()'


def test_variable_syntax():
    assert convert('[{$applicationname}]')[0] == '[{$applicationname}]()'


def test_set_variable_syntax():
    assert convert("[{SET alias='MyAlias'}]")[0] == "[{SET alias='MyAlias'}]()"


# ==================== Definition Lists ====================

def test_definition_list():
    assert convert(';Term:Definition')[0] == '**Term**: Definition'


def test_definition_list_comment():
    assert convert(';:Just a comment')[0] == ': Just a comment'


# ==================== Edge Cases ====================

def test_empty_input():
    md, warnings = convert('')
    assert md == ''
    assert warnings == []


def test_none_input():
    md, warnings = convert(None)
    assert md == ''
    assert warnings == []


def test_plain_text_passthrough():
    plain = 'Just some plain text.\n\nAnother paragraph.'
    assert convert(plain)[0] == plain


def test_markdown_formatting_passthrough():
    md = 'Some **bold** and *italic* text.'
    assert convert(md)[0] == md


def test_mixed_content():
    wiki = "!!! My Page\n\nThis is __bold__ and ''italic''.\n\n* Item 1\n* Item 2\n\n----\n\n[Click|http://example.com]"
    expected = "# My Page\n\nThis is **bold** and *italic*.\n\n* Item 1\n* Item 2\n\n---\n\n[Click](http://example.com)"
    assert convert(wiki)[0] == expected


def test_plugin_before_link_conversion():
    wiki = '[{TableOfContents}] and [MyPage]'
    expected = '[{TableOfContents}]() and [MyPage]()'
    assert convert(wiki)[0] == expected


# ==================== Heuristic Detection ====================

def test_is_likely_wiki_syntax_with_wiki_content():
    wiki = "!!! Heading\n\nSome ''italic'' text with [Link|Page]."
    assert is_likely_wiki_syntax(wiki) is True


def test_is_likely_wiki_syntax_with_markdown_content():
    md = '# Heading\n\nSome *italic* text with [Link](Page).'
    assert is_likely_wiki_syntax(md) is False


def test_is_likely_wiki_syntax_with_single_weak_signal():
    weak = 'Some __bold__ text here.'
    assert is_likely_wiki_syntax(weak) is False


def test_is_likely_wiki_syntax_null_and_empty():
    assert is_likely_wiki_syntax(None) is False
    assert is_likely_wiki_syntax('') is False
    assert is_likely_wiki_syntax('   ') is False


def test_is_likely_wiki_syntax_with_unconverted_plugins():
    wiki = "Some text with [{TableOfContents}] plugin.\n! And a heading."
    assert is_likely_wiki_syntax(wiki) is True


# ==================== Bulk File Operations ====================

def test_process_directory_converts_wiki_file():
    with tempfile.TemporaryDirectory() as d:
        txt = Path(d) / 'TestPage.txt'
        txt.write_text("!!! Hello\n\nSome ''italic'' and [Link|Page].\n", encoding='utf-8')

        stats = process_directory(d)

        assert stats['converted'] == 1
        assert not txt.exists(), '.txt should be deleted'
        md = Path(d) / 'TestPage.md'
        assert md.exists(), '.md should be created'
        content = md.read_text(encoding='utf-8')
        assert content.startswith('# Hello')


def test_process_directory_renames_markdown_txt_to_md():
    with tempfile.TemporaryDirectory() as d:
        txt = Path(d) / 'PlainPage.txt'
        original = '# Already markdown\n\nJust **bold** text.\n'
        txt.write_text(original, encoding='utf-8')

        stats = process_directory(d)

        assert stats['renamed'] == 1
        assert not txt.exists(), '.txt should be deleted after rename'
        md = Path(d) / 'PlainPage.md'
        assert md.exists(), '.md should be created'
        assert md.read_text(encoding='utf-8') == original, 'content should be unchanged'


def test_process_directory_rename_skips_if_md_exists():
    with tempfile.TemporaryDirectory() as d:
        txt = Path(d) / 'Existing.txt'
        txt.write_text('# Markdown in txt\n', encoding='utf-8')
        md = Path(d) / 'Existing.md'
        md.write_text('# Different content\n', encoding='utf-8')

        stats = process_directory(d)

        assert stats['skipped_md_exists'] == 1
        assert txt.exists(), '.txt should remain when .md already exists'


def test_process_directory_skips_if_md_exists():
    with tempfile.TemporaryDirectory() as d:
        txt = Path(d) / 'Existing.txt'
        txt.write_text("!!! Wiki\n''italic'' [Link|Page]", encoding='utf-8')
        md = Path(d) / 'Existing.md'
        md.write_text('# Already converted\n', encoding='utf-8')

        stats = process_directory(d)

        assert stats['skipped_md_exists'] == 1
        assert txt.exists(), 'should not delete .txt when .md exists'


def test_process_directory_dry_run():
    with tempfile.TemporaryDirectory() as d:
        txt = Path(d) / 'DryRun.txt'
        txt.write_text("!!! Wiki\n''italic'' [Link|Page]", encoding='utf-8')

        stats = process_directory(d, dry_run=True)

        assert stats['converted'] == 1
        assert txt.exists(), 'dry run should not delete .txt'
        assert not (Path(d) / 'DryRun.md').exists(), 'dry run should not create .md'


def test_process_directory_empty():
    with tempfile.TemporaryDirectory() as d:
        stats = process_directory(d)
        assert stats == {'converted': 0, 'renamed': 0, 'skipped_md_exists': 0, 'errors': 0}
