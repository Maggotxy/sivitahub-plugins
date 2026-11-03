"""Behavioral fixtures for the bilingual resource gate (no Paper required)."""

import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "check-locales.py"
SPEC = importlib.util.spec_from_file_location("check_locales", SCRIPT)
locales = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(locales)


class LocaleGateTest(unittest.TestCase):
    def load(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "messages.yml"
            path.write_text(text, encoding="utf-8")
            return locales.load_yaml(path)

    def test_nested_messages_allow_reflow_and_reordered_placeholders(self):
        data = self.load('''
zh_cn:
  panel:
    title: "你好 {player}"
    lines: ["{gold} 金币", "{season-merit} 功勋"]
en_us:
  panel:
    title: "Hello {player}"
    lines: ["Merit {season-merit}; Gold {gold}"]
''')
        self.assertEqual(2, locales.compare_group("fixture", data))

    def test_missing_locale_fails(self):
        with self.assertRaisesRegex(locales.LocaleError, "missing locale en_us"):
            locales.compare_group("fixture", {"zh_cn": {"title": "标题"}})

    def test_missing_nested_key_fails(self):
        with self.assertRaisesRegex(locales.LocaleError, "panel.title: missing English key"):
            locales.compare_group("fixture", {
                "zh_cn": {"panel": {"title": "标题", "body": "正文"}},
                "en_us": {"panel": {"body": "Body"}},
            })

    def test_duplicate_keys_fail_before_parser_discards_a_translation(self):
        with self.assertRaisesRegex(locales.LocaleError, "duplicate key"):
            self.load('menu:\n  title: "One"\n  title: "Two"\n')

    def test_missing_and_misspelled_placeholders_fail(self):
        for english in ("Saved", "Saved {players}"):
            with self.subTest(english=english):
                with self.assertRaisesRegex(locales.LocaleError, "placeholder mismatch"):
                    locales.compare_group("fixture", {
                        "zh_cn": {"saved": "已保存 {player}"},
                        "en_us": {"saved": english},
                    })

    def test_mismatch_in_a_list_with_hyphenated_token_fails(self):
        with self.assertRaisesRegex(locales.LocaleError, "placeholder mismatch"):
            locales.compare_group("fixture", {
                "zh_cn": {"lines": ["{season-merit}"]},
                "en_us": {"lines": ["{season_merit}"]},
            })

    def test_string_and_list_are_not_interchangeable(self):
        with self.assertRaisesRegex(locales.LocaleError, "string/list type mismatch"):
            locales.compare_group("fixture", {
                "zh_cn": {"body": "正文"}, "en_us": {"body": ["Body"]},
            })

    def test_boolean_like_keys_must_be_quoted(self):
        data = self.load('states:\n  on: "Visible"\n')
        with self.assertRaisesRegex(locales.LocaleError, "quote YAML boolean-like keys"):
            locales.flatten_messages(data, "fixture")
        self.assertEqual({"states.on": "Visible"},
                         locales.flatten_messages(self.load("states:\n  'on': Visible\n"), "fixture"))

    def test_dotted_bukkit_paths_are_supported_but_collisions_fail(self):
        self.assertEqual({"attribute.agility": "Agility"}, locales.flatten_messages(
            {"attribute.agility": "Agility"}, "fixture"))
        for data in (
            {"attribute.agility": "First", "attribute": {"agility": "Second"}},
            {"attribute": "First", "attribute.agility": "Second"},
        ):
            with self.subTest(data=data):
                with self.assertRaisesRegex(locales.LocaleError, "collid"):
                    locales.flatten_messages(data, "fixture")

    def test_empty_or_nontext_messages_fail(self):
        for value in ("", [], ["hello", False], 7, None, {}):
            with self.subTest(value=value):
                with self.assertRaises(locales.LocaleError):
                    locales.flatten_messages({"message": value}, "fixture")

    def test_recursive_alias_fails_with_a_diagnostic(self):
        with self.assertRaisesRegex(locales.LocaleError, "recursive YAML aliases"):
            locales.flatten_messages(self.load("menu: &menu\n  again: *menu\n"), "fixture")

    def test_embedded_config_group_is_discovered(self):
        data = self.load('''
enabled: false
messages:
  zh_cn: {hello: "你好"}
  en_us: {hello: "Hello"}
''')
        groups = locales.embedded_groups(data, "config.yml")
        self.assertEqual(["config.yml:messages"], list(groups))
        self.assertEqual(1, locales.compare_group("config.yml:messages", groups["config.yml:messages"]))

    def test_empty_checkout_cannot_report_a_false_green(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(locales.LocaleError, "Missing required language groups"):
                locales.discover_groups(Path(directory))


if __name__ == "__main__":
    unittest.main()
