#!/usr/bin/env python3
"""Marks the helper lexer rules of the official TPTP grammar as fragments.

The grammar at tptp.org is generated from the BNF, where the token rules and the character-class
rules that build them are written the same way. ANTLR treats every unmarked lexer rule as a token
it may return, and picks the first rule of the longest match, so `3` comes back as Exp_integer
rather than Integer and no parser rule accepts it. A lexer rule that no parser rule mentions is a
building block, not a token, which is exactly what `fragment` says.
"""
import re
import sys

END_OF_LEXER = "//# END THE LEXER RULES"


def main(src_path, dst_path):
    text = open(src_path).read()
    cut = text.index(END_OF_LEXER)
    lexer, parser = text[:cut], text[cut:]

    rule = re.compile(r"^(fragment\s+)?([A-Z]\w*)\s*:([^;]*);", re.M | re.S)
    names = [m.group(2) for m in rule.finditer(lexer)]

    # Whitespace and comments are matched and then thrown away, so no parser rule mentions them
    # even though they must stay tokens. The lexer command is what marks them.
    skipped = {m.group(2) for m in rule.finditer(lexer) if "->" in m.group(3)}

    # A name is a token if any parser rule mentions it; comments in the parser section quote the
    # BNF, so they are stripped first to keep quoted names from counting as uses.
    body = re.sub(r"//[^\n]*", "", parser)
    used = {n for n in names if re.search(r"\b" + n + r"\b", body)} | skipped

    def mark(m):
        if m.group(1) or m.group(2) in used:
            return m.group(0)
        return "fragment " + m.group(0)

    parser = admit_shadowed_words(parser)
    open(dst_path, "w").write(rule.sub(mark, lexer) + parser)
    print("tokens:   " + " ".join(sorted(used)))
    print("fragments: " + " ".join(sorted(set(names) - used)))




def admit_shadowed_words(parser):
    """Lets the words the grammar quotes be used as ordinary names again.

    A quoted word in a parser rule, such as the `unknown` of a formula's source, becomes a token of
    its own, and ANTLR prefers such a token to any lexer rule. `Lower_word` therefore stops matching
    those words, and a problem that names something `unknown` no longer parses even though TPTP
    allows it: the library's own `Axioms/NLP001+0.ax` writes `lexicalization(n8632096,unknown)`, and
    the syntax problems `SYN000+2` and its siblings give a formula the role `unknown`. Admitting
    every such word wherever a lower word is allowed restores them without disturbing the rules that
    quote them, which keep their own alternative.
    """
    words = sorted(set(re.findall(r"'([a-z][a-z0-9_]*)'", strip_comments(parser))))
    if not words:
        return parser
    alternatives = " | ".join("'" + w + "'" for w in words)
    parser += "\n//# Words the grammar quotes, admitted as ordinary names again.\n"
    parser += "shadowed_word : " + alternatives + ";\n"
    parser = parser.replace(
        "atomic_word : Lower_word  |  Single_quoted  |  Back_quoted;",
        "atomic_word : Lower_word  |  shadowed_word  |  Single_quoted  |  Back_quoted;")
    parser = parser.replace(
        "formula_role : Lower_word  |  Lower_word'-'general_term;",
        "formula_role : Lower_word  |  shadowed_word  |  Lower_word'-'general_term"
        "  |  shadowed_word'-'general_term;")
    return parser


def strip_comments(text):
    return re.sub(r"//[^\n]*", "", text)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
