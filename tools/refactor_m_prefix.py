#!/usr/bin/env python3
"""
Refactoring script to remove Hungarian notation (m_ prefix) from Java field names.

This script:
1. Finds all Java files with m_ prefixed fields in a given directory
2. Renames the fields to modern naming conventions
3. Updates all references to those fields throughout the file
4. Handles name conflicts by using 'this.' prefix for field access in conflicting scopes

Usage:
    python refactor_m_prefix.py <directory> [--dry-run] [--verbose]

Examples:
    python refactor_m_prefix.py wikantik-main/src/main/java/org/apache/wiki/filters --dry-run
    python refactor_m_prefix.py wikantik-main/src/main/java/org/apache/wiki/filters
"""

import argparse
import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional, Set, Tuple, List


@dataclass
class FieldRename:
    """Represents a field rename operation."""
    old_name: str
    new_name: str
    line_number: int
    has_conflict: bool = False


@dataclass
class MethodScope:
    """Represents a method/constructor with its parameter info and body range."""
    name: str
    start_line: int
    body_start: int  # Character position of opening {
    body_end: int    # Character position of closing }
    params: Set[str] = field(default_factory=set)


@dataclass
class RefactorResult:
    """Result of refactoring a single file."""
    file_path: str
    fields_renamed: list
    success: bool
    error_message: Optional[str] = None
    original_content: Optional[str] = None
    new_content: Optional[str] = None


class JavaFieldRefactorer:
    """Refactors Java files to remove m_ prefix from field names."""

    # Pattern to match field declarations with m_ prefix
    # Makes access modifier optional to handle package-private fields
    FIELD_DECL_PATTERN = re.compile(
        r'^(\s*)(?:(private|protected|public)\s+)?'
        r'((?:static\s+)?(?:final\s+)?(?:volatile\s+)?)?'
        r'(\S+(?:<[^>]+>)?(?:\[\])?)\s+'
        r'(m_\w+)\s*'
        r'(=.*?)?;',
        re.MULTILINE
    )

    # Pattern to find method/constructor signatures with their parameters
    METHOD_PATTERN = re.compile(
        r'(?:(?:public|private|protected)\s+)?'
        r'(?:(?:static|final|synchronized|abstract)\s+)*'
        r'(?:<[^>]+>\s+)?'  # Generic type parameters
        r'(?:[\w.<>\[\],\s]+\s+)?'  # Return type (not for constructors)
        r'(\w+)\s*'  # Method/constructor name
        r'\(([^)]*)\)\s*'  # Parameters
        r'(?:throws\s+[\w.,\s]+\s*)?'  # throws clause
        r'\{',  # Opening brace
        re.MULTILINE | re.DOTALL
    )

    # Reserved Java keywords
    JAVA_KEYWORDS = {
        'abstract', 'assert', 'boolean', 'break', 'byte', 'case', 'catch',
        'char', 'class', 'const', 'continue', 'default', 'do', 'double',
        'else', 'enum', 'extends', 'final', 'finally', 'float', 'for',
        'goto', 'if', 'implements', 'import', 'instanceof', 'int',
        'interface', 'long', 'native', 'new', 'package', 'private',
        'protected', 'public', 'return', 'short', 'static', 'strictfp',
        'super', 'switch', 'synchronized', 'this', 'throw', 'throws',
        'transient', 'try', 'void', 'volatile', 'while', 'true', 'false', 'null'
    }

    def __init__(self, verbose: bool = False):
        self.verbose = verbose

    def log(self, message: str):
        """Log a message if verbose mode is enabled."""
        if self.verbose:
            print(f"  {message}")

    def compute_new_name(self, old_name: str) -> str:
        """Compute the new field name by removing the m_ prefix."""
        if not old_name.startswith('m_'):
            return old_name

        new_name = old_name[2:]  # Remove 'm_' prefix

        # Handle edge case where removing prefix results in a keyword
        if new_name in self.JAVA_KEYWORDS:
            new_name = f"{new_name}Field"

        return new_name

    def find_matching_brace(self, content: str, start_pos: int) -> int:
        """
        Find the position of the closing brace that matches the opening brace at start_pos.
        Handles nested braces, strings, and comments.
        """
        if content[start_pos] != '{':
            return -1

        depth = 1
        pos = start_pos + 1
        length = len(content)

        while pos < length and depth > 0:
            char = content[pos]

            # Skip string literals
            if char == '"':
                pos += 1
                while pos < length:
                    if content[pos] == '\\':
                        pos += 2
                        continue
                    if content[pos] == '"':
                        pos += 1
                        break
                    pos += 1
                continue

            # Skip character literals
            if char == "'":
                pos += 1
                while pos < length:
                    if content[pos] == '\\':
                        pos += 2
                        continue
                    if content[pos] == "'":
                        pos += 1
                        break
                    pos += 1
                continue

            # Skip single-line comments
            if char == '/' and pos + 1 < length and content[pos + 1] == '/':
                pos = content.find('\n', pos)
                if pos == -1:
                    return -1
                pos += 1
                continue

            # Skip multi-line comments
            if char == '/' and pos + 1 < length and content[pos + 1] == '*':
                pos = content.find('*/', pos + 2)
                if pos == -1:
                    return -1
                pos += 2
                continue

            if char == '{':
                depth += 1
            elif char == '}':
                depth -= 1

            pos += 1

        return pos - 1 if depth == 0 else -1

    def parse_parameters(self, param_string: str) -> Set[str]:
        """Parse method parameters and return set of parameter names."""
        params = set()
        if not param_string.strip():
            return params

        # Remove annotations and split by comma
        # Handle generic types by tracking < > depth
        current_param = []
        depth = 0

        for char in param_string:
            if char == '<':
                depth += 1
            elif char == '>':
                depth -= 1
            elif char == ',' and depth == 0:
                param_text = ''.join(current_param).strip()
                if param_text:
                    # Extract parameter name (last word before any array brackets)
                    param_text = re.sub(r'\.\.\.$', '', param_text)  # Remove varargs
                    words = param_text.split()
                    if words:
                        name = words[-1].rstrip('[]')
                        if name and not name.startswith('@'):
                            params.add(name)
                current_param = []
                continue
            current_param.append(char)

        # Don't forget the last parameter
        param_text = ''.join(current_param).strip()
        if param_text:
            param_text = re.sub(r'\.\.\.$', '', param_text)
            words = param_text.split()
            if words:
                name = words[-1].rstrip('[]')
                if name and not name.startswith('@'):
                    params.add(name)

        return params

    def find_method_scopes(self, content: str) -> List[MethodScope]:
        """Find all method/constructor scopes in the content."""
        scopes = []

        for match in self.METHOD_PATTERN.finditer(content):
            method_name = match.group(1)
            param_string = match.group(2)
            params = self.parse_parameters(param_string)

            # Find the opening brace position
            brace_pos = match.end() - 1

            # Find the matching closing brace
            end_pos = self.find_matching_brace(content, brace_pos)

            if end_pos != -1:
                line_number = content[:match.start()].count('\n') + 1
                scopes.append(MethodScope(
                    name=method_name,
                    start_line=line_number,
                    body_start=brace_pos,
                    body_end=end_pos,
                    params=params
                ))

        return scopes

    def find_fields_to_rename(self, content: str) -> list:
        """Find all fields with m_ prefix that need to be renamed."""
        fields = []

        for match in self.FIELD_DECL_PATTERN.finditer(content):
            old_name = match.group(5)
            new_name = self.compute_new_name(old_name)
            line_number = content[:match.start()].count('\n') + 1

            fields.append(FieldRename(
                old_name=old_name,
                new_name=new_name,
                line_number=line_number
            ))

        return fields

    def find_conflicting_scopes(self, content: str, new_name: str,
                                 method_scopes: List[MethodScope]) -> List[MethodScope]:
        """Find method scopes that have a parameter conflicting with the new field name."""
        conflicting = []
        for scope in method_scopes:
            if new_name in scope.params:
                conflicting.append(scope)
        return conflicting

    def is_in_scope(self, pos: int, scopes: List[MethodScope]) -> Optional[MethodScope]:
        """Check if a position is inside any of the given scopes."""
        for scope in scopes:
            if scope.body_start < pos < scope.body_end:
                return scope
        return None

    def rename_field_in_content(self, content: str, old_name: str, new_name: str,
                                 conflicting_scopes: List[MethodScope]) -> str:
        """
        Rename a field throughout the file content.

        For usages inside conflicting scopes (where a parameter has same name as new field),
        use 'this.' prefix for field access.
        """
        result = []
        last_pos = 0

        # Pattern to find the field name as a word
        pattern = re.compile(rf'\b{re.escape(old_name)}\b')

        for match in pattern.finditer(content):
            start = match.start()
            end = match.end()

            # Add content before this match
            result.append(content[last_pos:start])

            # Check if this is the field declaration itself
            line_start = content.rfind('\n', 0, start) + 1
            line = content[line_start:content.find('\n', end)]
            # Match field declarations with or without access modifiers (package-private)
            is_declaration = bool(re.match(
                r'\s*(?:(?:private|protected|public)\s+)?(?:(?:static|final|volatile)\s+)*\w+(?:<[^>]+>)?(?:\[\])?\s+' + re.escape(old_name),
                line
            ))

            # Check if this usage is inside a conflicting scope
            in_conflicting_scope = self.is_in_scope(start, conflicting_scopes)

            # Check if already prefixed with 'this.'
            prefix_check = content[max(0, start-5):start]
            already_has_this = prefix_check.endswith('this.')

            # Check if this is a method call on an object (e.g., obj.m_foo())
            # In this case, don't prefix with this.
            is_method_call_on_obj = False
            if start > 0:
                prev_char_pos = start - 1
                while prev_char_pos >= 0 and content[prev_char_pos] in ' \t':
                    prev_char_pos -= 1
                if prev_char_pos >= 0 and content[prev_char_pos] == '.':
                    # Check if it's not 'this.'
                    check_start = max(0, prev_char_pos - 4)
                    if not content[check_start:prev_char_pos+1].endswith('this.'):
                        is_method_call_on_obj = True

            if is_declaration:
                # Field declaration - just rename
                result.append(new_name)
            elif already_has_this:
                # Already has this. prefix - just rename
                result.append(new_name)
            elif is_method_call_on_obj:
                # Method call on another object - just rename
                result.append(new_name)
            elif in_conflicting_scope is not None:
                # Inside a conflicting scope - use this. prefix
                result.append(f'this.{new_name}')
            else:
                # Normal case - just rename
                result.append(new_name)

            last_pos = end

        # Add remaining content
        result.append(content[last_pos:])

        return ''.join(result)

    def refactor_file(self, file_path: str, dry_run: bool = False) -> RefactorResult:
        """Refactor a single Java file to remove m_ prefixes."""
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                original_content = f.read()
        except Exception as e:
            return RefactorResult(
                file_path=file_path,
                fields_renamed=[],
                success=False,
                error_message=f"Failed to read file: {e}"
            )

        # Find all fields to rename
        fields = self.find_fields_to_rename(original_content)

        if not fields:
            return RefactorResult(
                file_path=file_path,
                fields_renamed=[],
                success=True,
                original_content=original_content,
                new_content=original_content
            )

        # Apply renames
        new_content = original_content
        for field_info in fields:
            # Recalculate method scopes for each field to handle position drift
            # after previous renames
            method_scopes = self.find_method_scopes(new_content)

            # Find scopes with conflicting parameters for this field
            conflicting_scopes = self.find_conflicting_scopes(
                new_content, field_info.new_name, method_scopes
            )

            if conflicting_scopes:
                field_info.has_conflict = True
                self.log(f"Conflicts found for {field_info.old_name} -> {field_info.new_name}:")
                for scope in conflicting_scopes:
                    self.log(f"  - Method '{scope.name}' at line {scope.start_line}")

            self.log(f"Renaming {field_info.old_name} -> {field_info.new_name}")
            new_content = self.rename_field_in_content(
                new_content,
                field_info.old_name,
                field_info.new_name,
                conflicting_scopes
            )

        # Write the file if not dry run
        if not dry_run and new_content != original_content:
            try:
                with open(file_path, 'w', encoding='utf-8') as f:
                    f.write(new_content)
            except Exception as e:
                return RefactorResult(
                    file_path=file_path,
                    fields_renamed=fields,
                    success=False,
                    error_message=f"Failed to write file: {e}",
                    original_content=original_content,
                    new_content=new_content
                )

        return RefactorResult(
            file_path=file_path,
            fields_renamed=fields,
            success=True,
            original_content=original_content,
            new_content=new_content
        )

    def find_java_files_with_m_prefix(self, directory: str) -> list:
        """Find all Java files in a directory that contain m_ prefixed fields."""
        files_with_prefix = []

        for root, _, files in os.walk(directory):
            for filename in files:
                if filename.endswith('.java'):
                    file_path = os.path.join(root, filename)
                    try:
                        with open(file_path, 'r', encoding='utf-8') as f:
                            content = f.read()
                        if self.FIELD_DECL_PATTERN.search(content):
                            files_with_prefix.append(file_path)
                    except Exception as e:
                        print(f"Warning: Could not read {file_path}: {e}")

        return sorted(files_with_prefix)

    def refactor_directory(self, directory: str, dry_run: bool = False) -> list:
        """Refactor all Java files in a directory."""
        results = []
        files = self.find_java_files_with_m_prefix(directory)

        print(f"Found {len(files)} files with m_ prefixed fields in {directory}")

        for file_path in files:
            relative_path = os.path.relpath(file_path, directory)
            print(f"\nProcessing: {relative_path}")

            result = self.refactor_file(file_path, dry_run=dry_run)
            results.append(result)

            if result.success:
                if result.fields_renamed:
                    print(f"  Renamed {len(result.fields_renamed)} fields:")
                    for field_info in result.fields_renamed:
                        conflict_marker = " (has parameter conflict)" if field_info.has_conflict else ""
                        print(f"    {field_info.old_name} -> {field_info.new_name}{conflict_marker}")
                else:
                    print("  No fields to rename")
            else:
                print(f"  ERROR: {result.error_message}")

        return results


def print_summary(results: list, dry_run: bool):
    """Print a summary of the refactoring results."""
    total_files = len(results)
    successful = sum(1 for r in results if r.success)
    failed = total_files - successful
    total_fields = sum(len(r.fields_renamed) for r in results if r.success)

    print("\n" + "=" * 60)
    print("SUMMARY")
    print("=" * 60)
    print(f"Mode: {'DRY RUN (no files modified)' if dry_run else 'LIVE'}")
    print(f"Files processed: {total_files}")
    print(f"Successful: {successful}")
    print(f"Failed: {failed}")
    print(f"Total fields renamed: {total_fields}")

    if failed > 0:
        print("\nFailed files:")
        for r in results:
            if not r.success:
                print(f"  {r.file_path}: {r.error_message}")


def main():
    parser = argparse.ArgumentParser(
        description='Remove m_ prefix from Java field names',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    parser.add_argument(
        'directory',
        help='Directory containing Java files to refactor'
    )
    parser.add_argument(
        '--dry-run',
        action='store_true',
        help='Show what would be changed without modifying files'
    )
    parser.add_argument(
        '--verbose', '-v',
        action='store_true',
        help='Enable verbose output'
    )
    parser.add_argument(
        '--file',
        help='Process a single file instead of a directory'
    )

    args = parser.parse_args()

    refactorer = JavaFieldRefactorer(verbose=args.verbose)

    if args.file:
        if not os.path.isfile(args.file):
            print(f"Error: File not found: {args.file}")
            sys.exit(1)
        result = refactorer.refactor_file(args.file, dry_run=args.dry_run)
        print_summary([result], args.dry_run)
    else:
        if not os.path.isdir(args.directory):
            print(f"Error: Directory not found: {args.directory}")
            sys.exit(1)
        results = refactorer.refactor_directory(args.directory, dry_run=args.dry_run)
        print_summary(results, args.dry_run)

    sys.exit(0)


if __name__ == '__main__':
    main()
