#!/usr/bin/env python3
"""Bounded Java-to-JDBC source analysis for the DDL consumer gate.

Recognizes literals/text blocks, string concatenation/conditionals, visible local
bindings, final class constants, and the reviewed SchemaInspector.Schema lookup
``schema.tables().get(exactName).qualifiedName()``. A table counts only when the
resulting SQL reaches a Statement execution or an executed PreparedStatement.
Unresolved table expressions never expand to all manifest tables. This is a
source-wiring check, not a Java type checker or a whole-program reachability proof.
Java lexical rules: https://docs.oracle.com/javase/specs/jls/se25/html/jls-3.html
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys
from typing import Iterable

UNKNOWN = "__UNRESOLVED_JAVA_EXPRESSION__"
TABLE = re.compile(r"rpg_[a-z0-9_]+\Z")
MAX_VARIANTS = 32


@dataclass(frozen=True)
class Token:
    kind: str
    value: str

    @property
    def mark(self) -> str:
        return self.value if self.kind == "symbol" else ""


def unicode_input(source: str) -> str:
    """Decode eligible Java Unicode escapes before recognizing comments/strings."""
    result: list[str] = []
    i = backslashes = 0
    escaped = False
    while i < len(source):
        ch = source[i]
        if ch == "\\" and (escaped or backslashes % 2 == 0):
            match = re.match(r"\\u+([0-9a-fA-F]{4})", source[i:])
            if match:
                ch = chr(int(match.group(1), 16))
                result.append(ch)
                i += len(match.group(0))
                backslashes = backslashes + 1 if ch == "\\" else 0
                escaped = True
                continue
        result.append(ch)
        i += 1
        backslashes = backslashes + 1 if ch == "\\" else 0
        escaped = False
    return "".join(result)


def java_escapes(value: str) -> str:
    result: list[str] = []
    i = 0
    escapes = {"b": "\b", "t": "\t", "n": "\n", "f": "\f", "r": "\r", "s": " ", '"': '"', "'": "'", "\\": "\\"}
    while i < len(value):
        if value[i] != "\\":
            result.append(value[i]); i += 1; continue
        i += 1
        if i == len(value):
            raise ValueError("Unterminated Java escape")
        char = value[i]
        if char in escapes:
            result.append(escapes[char]); i += 1
        elif char in "\r\n":
            i += 2 if char == "\r" and value[i:i + 2] == "\r\n" else 1
        elif char in "01234567":
            size = 3 if char in "0123" else 2
            end = i + 1
            while end < min(i + size, len(value)) and value[end] in "01234567":
                end += 1
            result.append(chr(int(value[i:end], 8))); i = end
        else:
            raise ValueError("Unsupported Java escape")
    return "".join(result)


def tokenize(source: str) -> list[Token]:
    source = unicode_input(source)
    result: list[Token] = []
    i = 0
    while i < len(source):
        if source[i].isspace(): i += 1; continue
        if source.startswith("//", i):
            end = source.find("\n", i + 2); i = len(source) if end < 0 else end + 1; continue
        if source.startswith("/*", i):
            end = source.find("*/", i + 2)
            if end < 0: raise ValueError("Unterminated Java comment")
            i = end + 2; continue
        if source[i] in "\"'":
            quote = '"""' if source.startswith('"""', i) else source[i]
            start = i + len(quote); i = start
            while i < len(source):
                if source[i] == "\\": i += 2; continue
                if source.startswith(quote, i): break
                i += 1
            if i >= len(source): raise ValueError("Unterminated Java literal")
            raw = source[start:i]
            result.append(Token("str" if quote != "'" else "char", java_escapes(raw)))
            i += len(quote); continue
        match = re.match(r"[A-Za-z_$][A-Za-z0-9_$]*|\d+", source[i:])
        if match:
            value = match.group(0); result.append(Token("id" if not value[0].isdigit() else "number", value)); i += len(value); continue
        pair = source[i:i + 2]
        if pair in {"->", "==", "!=", "+=", "-=", "++", "--", "&&", "||", "::", "<=", ">="}:
            result.append(Token("symbol", pair)); i += 2
        else:
            result.append(Token("symbol", source[i])); i += 1
    return result


@dataclass(frozen=True)
class Expr:
    kind: str
    name: str = ""
    args: tuple["Expr", ...] = ()


def split_top(tokens: list[Token], separator: str) -> list[list[Token]]:
    parts: list[list[Token]] = []
    start = depth = 0
    for i, token in enumerate(tokens):
        if token.mark in {"(", "[", "{"}: depth += 1
        elif token.mark in {")", "]", "}"}: depth -= 1
        elif token.mark == separator and depth == 0:
            parts.append(tokens[start:i]); start = i + 1
    parts.append(tokens[start:]); return parts


def parse_expr(tokens: list[Token]) -> Expr:
    if not tokens: return Expr("unknown")
    while len(tokens) >= 2 and tokens[0].mark == "(":
        depth = 0; close = -1
        for i, token in enumerate(tokens):
            if token.mark == "(": depth += 1
            elif token.mark == ")":
                depth -= 1
                if depth == 0: close = i; break
        if close != len(tokens) - 1: break
        tokens = tokens[1:-1]
    depth = 0; question = None; nested = 0
    for i, token in enumerate(tokens):
        if token.mark in {"(", "[", "{"}: depth += 1
        elif token.mark in {")", "]", "}"}: depth -= 1
        elif depth == 0 and token.mark == "?":
            if question is None: question = i
            else: nested += 1
        elif depth == 0 and token.mark == ":" and question is not None:
            if nested: nested -= 1
            else:
                return Expr("conditional", args=(parse_expr(tokens[:question]), parse_expr(tokens[question + 1:i]), parse_expr(tokens[i + 1:])))
    parts = split_top(tokens, "+")
    if len(parts) > 1: return Expr("concat", args=tuple(parse_expr(part) for part in parts))
    if not tokens: return Expr("unknown")
    i = 1
    if tokens[0].kind == "str": node = Expr("literal", tokens[0].value)
    elif tokens[0].kind == "id": node = Expr("ref", tokens[0].value)
    elif tokens[0].mark == "(":
        depth = 1
        while i < len(tokens) and depth:
            if tokens[i].mark == "(": depth += 1
            elif tokens[i].mark == ")": depth -= 1
            i += 1
        if depth: return Expr("unknown")
        node = parse_expr(tokens[1:i - 1])
    else: return Expr("unknown")
    while i < len(tokens):
        if tokens[i].mark == "." and i + 1 < len(tokens) and tokens[i + 1].kind == "id":
            node = Expr("member", tokens[i + 1].value, (node,)); i += 2
        elif tokens[i].mark == "(":
            start = i + 1; depth = 1; i += 1
            while i < len(tokens) and depth:
                if tokens[i].mark == "(": depth += 1
                elif tokens[i].mark == ")": depth -= 1
                i += 1
            if depth: return Expr("unknown")
            arguments = tuple(parse_expr(part) for part in split_top(tokens[start:i - 1], ",") if part)
            if node.kind == "member": node = Expr("call", node.name, (node.args[0],) + arguments)
            elif node.kind == "ref": node = Expr("call", node.name, (Expr("none"),) + arguments)
            else: return Expr("unknown")
        else: return Expr("unknown")
    return node


@dataclass(frozen=True)
class Value:
    kind: str
    strings: frozenset[str] = frozenset()


UNRESOLVED = Value("unknown", frozenset({UNKNOWN}))


def strings(value: Value) -> frozenset[str]:
    return value.strings if value.kind == "string" else UNRESOLVED.strings


def joined(values: Iterable[Value]) -> Value:
    variants = {""}
    for value in values:
        variants = {left + right for left in variants for right in strings(value)}
        if len(variants) > MAX_VARIANTS: return UNRESOLVED
    return Value("string", frozenset(variants))


@dataclass
class Scope:
    parent: int | None
    kind: str
    start: int
    end: int = 0


@dataclass
class Binding:
    name: str
    scope: int
    at: int
    expression: Expr
    eval_scope: int
    declared_type: str = ""
    constant: bool = False
    conditional_scope: int | None = None


class JavaConsumers:
    def __init__(self, source: str):
        self.tokens = tokenize(source)
        self.matches: dict[int, int] = {}
        stack: list[int] = []
        for i, token in enumerate(self.tokens):
            if token.mark in {"(", "[", "{"}: stack.append(i)
            elif token.mark in {")", "]", "}"}:
                if not stack: raise ValueError("Unbalanced Java delimiter")
                start = stack.pop()
                if self.tokens[start].mark != {")": "(", "]": "[", "}": "{"}[token.mark]: raise ValueError("Mismatched Java delimiter")
                self.matches[start] = i; self.matches[i] = start
        if stack: raise ValueError("Unbalanced Java source")
        self.scopes = [Scope(None, "root", 0, len(self.tokens))]
        self.scope_at: list[int] = []
        self.bindings: list[Binding] = []
        self.methods: dict[tuple[int, str], tuple[Expr, int, int]] = {}
        active = 0
        for i, token in enumerate(self.tokens):
            self.scope_at.append(active)
            if token.mark == "{":
                boundary = i - 1
                while boundary >= 0 and self.tokens[boundary].mark not in {";", "{", "}"}: boundary -= 1
                prefix = self.tokens[boundary + 1:i]
                kind = "class" if any(t.kind == "id" and t.value in {"class", "interface", "enum", "record"} for t in prefix) else "block"
                scope = len(self.scopes)
                self.scopes.append(Scope(active, kind, i, self.matches[i])); active = scope
                if kind != "class": self._parameters(i, scope)
            elif token.mark == "}": active = self.scopes[active].parent or 0
        self._collect_bindings()

    def _parameters(self, brace: int, scope: int) -> None:
        end = brace - 1
        if end >= 0 and self.tokens[end].mark == "->":
            start = end - 1
            if self.tokens[start].mark == ")":
                parameters = split_top(self.tokens[self.matches[start] + 1:start], ",")
            else:
                parameters = [self.tokens[start:end]]
            for parameter in parameters:
                if parameter and parameter[-1].kind == "id":
                    self.bindings.append(Binding(parameter[-1].value, scope, brace - 1, Expr("unknown"), scope,
                                                "".join(t.value for t in parameter[:-1])))
            return
        while end >= 0 and self.tokens[end].mark not in {")", "{", "}", ";", "->"}: end -= 1
        if end < 0 or self.tokens[end].mark != ")": return
        start = self.matches[end]
        if start == 0 or self.tokens[start - 1].kind != "id": return
        name = self.tokens[start - 1].value
        if name in {"if", "while", "for", "switch", "synchronized", "try", "catch"}: return
        self.scopes[scope].kind = "method"
        parameters = split_top(self.tokens[start + 1:end], ",")
        for parameter in parameters:
            if len(parameter) >= 2 and parameter[-1].kind == "id":
                self.bindings.append(Binding(parameter[-1].value, scope, brace - 1, Expr("unknown"), scope,
                                             "".join(t.value for t in parameter[:-1])))
        # Only a simple, parameterless return expression can be propagated.
        # Nested lambda/class returns and unrelated classes cannot donate SQL.
        cursor = brace + 1
        if parameters == [[]] and cursor < self.scopes[scope].end and self.tokens[cursor].value == "return":
            stop = self.expression_end(cursor + 1)
            if stop == self.scopes[scope].end - 1:
                owner = next((candidate for candidate in self.ancestors(scope) if self.scopes[candidate].kind == "class"), None)
                if owner is not None:
                    self.methods[(owner, name)] = (parse_expr(self.tokens[cursor + 1:stop]), cursor, scope)

    def expression_end(self, start: int) -> int:
        i = start
        while i < len(self.tokens):
            if self.tokens[i].mark in {"(", "[", "{"}: i = self.matches[i] + 1; continue
            if self.tokens[i].mark in {";", ",", ")", "}"}: return i
            i += 1
        return i

    def ancestors(self, scope: int) -> list[int]:
        result = []
        while True:
            result.append(scope)
            parent = self.scopes[scope].parent
            if parent is None: return result
            scope = parent

    def lookup(self, name: str, scope: int, at: int) -> Binding | None:
        for candidate_scope in self.ancestors(scope):
            candidates = [b for b in self.bindings if b.scope == candidate_scope and b.name == name
                          and (b.at < at or b.constant)]
            if candidates: return max(candidates, key=lambda b: b.at)
        return None

    def _collect_bindings(self) -> None:
        for i, token in enumerate(self.tokens):
            if token.mark not in {"=", "+="} or i < 2 or self.tokens[i - 1].kind != "id": continue
            if self.tokens[i - 2].mark == ".": continue
            name = self.tokens[i - 1].value; scope = self.scope_at[i]
            start = i - 2
            while start >= 0 and self.tokens[start].mark not in {";", "{", "}", "(", ")", ",", ":"}: start -= 1
            prefix = self.tokens[start + 1:i - 1]
            declaration = bool(prefix and prefix[-1].kind == "id" and prefix[-1].value not in {"return", "yield", "throw", "case"})
            stop = self.expression_end(i + 1)
            expression = parse_expr(self.tokens[i + 1:stop])
            if token.mark == "+=": expression = Expr("concat", args=(Expr("ref", name), expression))
            declared_type = "".join(t.value for t in prefix if t.value not in {"static", "final", "private", "public", "protected"}) if declaration else ""
            constant = declaration and self.scopes[scope].kind == "class" and any(t.kind == "id" and t.value == "final" for t in prefix)
            if self.scopes[scope].kind == "class" and not constant: expression = Expr("unknown")
            conditional_scope = None
            if not declaration:
                previous = self.lookup(name, scope, i)
                if previous is None: continue
                declared_type = previous.declared_type
                owner = previous.scope
                conditional_scope = scope if scope != owner else None
            else: owner = scope
            self.bindings.append(Binding(name, owner, i, expression, scope, declared_type, constant, conditional_scope))

    def evaluate(self, node: Expr, scope: int, at: int, seen: frozenset[int] = frozenset()) -> Value:
        if len(seen) > 32: return UNRESOLVED
        if node.kind == "literal": return Value("string", frozenset({node.name}))
        if node.kind == "concat": return joined(self.evaluate(arg, scope, at, seen) for arg in node.args)
        if node.kind == "conditional":
            condition = node.args[0]
            if condition.kind == "ref" and condition.name in {"true", "false"}:
                return self.evaluate(node.args[1 if condition.name == "true" else 2], scope, at, seen)
            left, right = (self.evaluate(arg, scope, at, seen) for arg in node.args[1:])
            variants = strings(left) | strings(right)
            return Value("string", variants) if len(variants) <= MAX_VARIANTS else UNRESOLVED
        if node.kind == "member" and node.args[0] == Expr("ref", "this"):
            node = Expr("ref", node.name)
        if node.kind == "ref":
            binding = self.lookup(node.name, scope, at)
            if binding is None or id(binding) in seen: return UNRESOLVED
            if binding.conditional_scope is not None and binding.conditional_scope not in self.ancestors(scope): return UNRESOLVED
            value = self.evaluate(binding.expression, binding.eval_scope, binding.at, seen | {id(binding)})
            if value.kind != "unknown": return value
            declared_type = binding.declared_type.removeprefix("final")
            if declared_type in {"SchemaInspector.Schema", "cn.originalrpg.tools.dbmigration.SchemaInspector.Schema"}: return Value("schema")
            if declared_type in {"Statement", "java.sql.Statement"}: return Value("statement")
            if declared_type in {"Connection", "java.sql.Connection"}: return Value("connection")
            if declared_type and declared_type != "var": return Value("other")
            return UNRESOLVED
        if node.kind != "call": return UNRESOLVED
        receiver = self.evaluate(node.args[0], scope, at, seen)
        arguments = node.args[1:]
        if node.name == "prepareStatement" and arguments and node.args[0].kind != "none":
            if receiver.kind not in {"unknown", "connection"}: return UNRESOLVED
            return Value("prepared", strings(self.evaluate(arguments[0], scope, at, seen)))
        if node.name == "createStatement" and node.args[0].kind != "none" and receiver.kind in {"unknown", "connection"}:
            return Value("statement")
        if node.name == "tables" and not arguments and receiver.kind == "schema": return Value("schema-tables")
        if node.name == "get" and len(arguments) == 1 and receiver.kind == "schema-tables":
            names = self.evaluate(arguments[0], scope, at, seen)
            if names.kind == "string" and len(names.strings) == 1 and all(TABLE.fullmatch(name) for name in names.strings):
                return Value("schema-table", names.strings)
        if node.name == "qualifiedName" and not arguments and receiver.kind == "schema-table":
            return Value("string", frozenset('"' + name + '"' for name in receiver.strings))
        if not arguments and node.args[0].kind == "none":
            for owner in self.ancestors(scope):
                helper = self.methods.get((owner, node.name))
                if helper is not None:
                    expression, position, method_scope = helper
                    if id(expression) in seen: return UNRESOLVED
                    return self.evaluate(expression, method_scope, position, seen | {id(expression)})
        return UNRESOLVED

    def primary_start(self, end: int) -> int:
        start = end
        if self.tokens[end].mark == ")":
            start = self.matches[end]
            if start > 0 and self.tokens[start - 1].kind == "id": start -= 1
        while start >= 2 and self.tokens[start - 1].mark == ".": start = self.primary_start(start - 2)
        return start

    def consumed_tables(self) -> set[str]:
        consumed: set[str] = set()
        batches: dict[int, set[str]] = {}
        methods = {"execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch", "addBatch"}
        for i, token in enumerate(self.tokens):
            if token.kind != "id" or token.value not in methods or i < 2 or i + 1 >= len(self.tokens): continue
            if self.tokens[i - 1].mark != "." or self.tokens[i + 1].mark != "(": continue
            receiver_expr = parse_expr(self.tokens[self.primary_start(i - 2):i - 1])
            scope = self.scope_at[i]
            receiver = self.evaluate(receiver_expr, scope, i)
            if receiver.kind not in {"statement", "prepared"}: continue
            close = self.matches[i + 1]
            args = [parse_expr(part) for part in split_top(self.tokens[i + 2:close], ",") if part]
            sql = receiver.strings if receiver.kind == "prepared" and not args else strings(self.evaluate(args[0], scope, i)) if args else frozenset()
            tables = set().union(*(sql_tables(value) for value in sql)) if sql else set()
            binding = self.lookup(receiver_expr.name, scope, i) if receiver_expr.kind == "ref" else None
            if token.value == "addBatch":
                if binding is not None: batches.setdefault(id(binding), set()).update(tables)
            elif token.value in {"executeBatch", "executeLargeBatch"}:
                if binding is not None: consumed.update(batches.get(id(binding), set()))
            else: consumed.update(tables)
        return consumed


def sql_tables(sql: str) -> set[str]:
    """Find table identifiers in DML positions, excluding SQL comments and values."""
    tokens: list[tuple[str, bool]] = []
    i = 0
    while i < len(sql):
        if sql[i].isspace(): i += 1; continue
        if sql.startswith("--", i):
            end = sql.find("\n", i + 2); i = len(sql) if end < 0 else end + 1; continue
        if sql.startswith("/*", i):
            depth = 1; i += 2
            while i < len(sql) and depth:
                if sql.startswith("/*", i): depth += 1; i += 2
                elif sql.startswith("*/", i): depth -= 1; i += 2
                else: i += 1
            continue
        if sql[i] == "'":
            i += 1
            while i < len(sql):
                if sql.startswith("''", i): i += 2
                elif sql[i] == "'": i += 1; break
                elif sql[i] == "\\": i += 2
                else: i += 1
            tokens.append(("<value>", False)); continue
        if sql[i] == '$':
            tag = re.match(r"\$(?:[A-Za-z_][A-Za-z0-9_]*)?\$", sql[i:])
            if tag:
                end = sql.find(tag.group(0), i + len(tag.group(0)))
                i = len(sql) if end < 0 else end + len(tag.group(0)); tokens.append(("<value>", False)); continue
        if sql[i] == '"':
            i += 1; value = []
            while i < len(sql):
                if sql.startswith('""', i): value.append('"'); i += 2
                elif sql[i] == '"': i += 1; break
                else: value.append(sql[i]); i += 1
            tokens.append(("".join(value), True)); continue
        match = re.match(r"[A-Za-z_][A-Za-z0-9_$]*", sql[i:])
        if match: tokens.append((match.group(0), False)); i += len(match.group(0))
        else: tokens.append((sql[i], False)); i += 1
    if not tokens or tokens[0][0].upper() not in {"SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "WITH"}: return set()
    result = set()
    for pos, (token, quoted) in enumerate(tokens[:-1]):
        if quoted or token.upper() not in {"FROM", "JOIN", "INTO", "UPDATE"}: continue
        cursor = pos + 1
        if tokens[cursor][0].upper() == "ONLY" and cursor + 1 < len(tokens): cursor += 1
        candidate = tokens[cursor][0]
        while cursor + 2 < len(tokens) and tokens[cursor + 1] == (".", False):
            cursor += 2; candidate = tokens[cursor][0]
        if TABLE.fullmatch(candidate): result.add(candidate)
    return result


def scan_source(source: str) -> set[str]:
    return JavaConsumers(source).consumed_tables()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--files-from", required=True, type=Path, help="One Java source path per line")
    args = parser.parse_args()
    consumed = set()
    for line in args.files_from.read_text(encoding="utf-8").splitlines():
        if not line: continue
        path = Path(line)
        try: consumed.update(scan_source(path.read_text(encoding="utf-8")))
        except (OSError, ValueError, RecursionError) as error:
            print(f"DDL consumer scan failed for {path}: {error}", file=sys.stderr)
            return 2
    for table in sorted(consumed): print(table)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
