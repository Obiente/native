use std::env;
use std::fs;
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::process::ExitCode;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct Rule {
    codepoint: u32,
    name: &'static str,
    replacement: &'static str,
}

const RULES: &[Rule] = &[
    Rule {
        codepoint: 0x00a0,
        name: "no-break space",
        replacement: "regular space",
    },
    Rule {
        codepoint: 0x00ad,
        name: "soft hyphen",
        replacement: "remove it or use ASCII hyphen-minus",
    },
    Rule {
        codepoint: 0x2007,
        name: "figure space",
        replacement: "regular space",
    },
    Rule {
        codepoint: 0x200b,
        name: "zero-width space",
        replacement: "remove it",
    },
    Rule {
        codepoint: 0x200c,
        name: "zero-width non-joiner",
        replacement: "remove it or document an intentional language requirement",
    },
    Rule {
        codepoint: 0x200d,
        name: "zero-width joiner",
        replacement: "remove it or document an intentional language requirement",
    },
    Rule {
        codepoint: 0x200e,
        name: "left-to-right mark",
        replacement: "remove it or use an ASCII Unicode escape for intentional handling",
    },
    Rule {
        codepoint: 0x200f,
        name: "right-to-left mark",
        replacement: "remove it or use an ASCII Unicode escape for intentional handling",
    },
    Rule {
        codepoint: 0x2010,
        name: "Unicode hyphen",
        replacement: "ASCII hyphen-minus (-)",
    },
    Rule {
        codepoint: 0x2011,
        name: "non-breaking hyphen",
        replacement: "ASCII hyphen-minus (-)",
    },
    Rule {
        codepoint: 0x2012,
        name: "figure dash",
        replacement: "ASCII hyphen-minus (-)",
    },
    Rule {
        codepoint: 0x2013,
        name: "en dash",
        replacement: "ASCII hyphen-minus (-)",
    },
    Rule {
        codepoint: 0x2014,
        name: "em dash",
        replacement: "ASCII hyphen-minus (-)",
    },
    Rule {
        codepoint: 0x2015,
        name: "horizontal bar",
        replacement: "ASCII hyphen-minus (-)",
    },
    Rule {
        codepoint: 0x2018,
        name: "left single quotation mark",
        replacement: "ASCII apostrophe (')",
    },
    Rule {
        codepoint: 0x2019,
        name: "right single quotation mark",
        replacement: "ASCII apostrophe (')",
    },
    Rule {
        codepoint: 0x201a,
        name: "single low-9 quotation mark",
        replacement: "ASCII apostrophe (')",
    },
    Rule {
        codepoint: 0x201b,
        name: "single high-reversed-9 quotation mark",
        replacement: "ASCII apostrophe (')",
    },
    Rule {
        codepoint: 0x201c,
        name: "left double quotation mark",
        replacement: "ASCII quotation mark",
    },
    Rule {
        codepoint: 0x201d,
        name: "right double quotation mark",
        replacement: "ASCII quotation mark",
    },
    Rule {
        codepoint: 0x201e,
        name: "double low-9 quotation mark",
        replacement: "ASCII quotation mark",
    },
    Rule {
        codepoint: 0x201f,
        name: "double high-reversed-9 quotation mark",
        replacement: "ASCII quotation mark",
    },
    Rule {
        codepoint: 0x2026,
        name: "horizontal ellipsis",
        replacement: "three ASCII periods (...)",
    },
    Rule {
        codepoint: 0x202a,
        name: "left-to-right embedding control",
        replacement: "remove it or use an ASCII Unicode escape for intentional handling",
    },
    Rule {
        codepoint: 0x202b,
        name: "right-to-left embedding control",
        replacement: "remove it or use an ASCII Unicode escape for intentional handling",
    },
    Rule {
        codepoint: 0x202c,
        name: "pop directional formatting control",
        replacement: "remove it or use an ASCII Unicode escape for intentional handling",
    },
    Rule {
        codepoint: 0x202d,
        name: "left-to-right override control",
        replacement: "remove it or use an ASCII Unicode escape for intentional handling",
    },
    Rule {
        codepoint: 0x202e,
        name: "right-to-left override control",
        replacement: "remove it or use an ASCII Unicode escape for intentional handling",
    },
    Rule {
        codepoint: 0x202f,
        name: "narrow no-break space",
        replacement: "regular space",
    },
    Rule {
        codepoint: 0x2060,
        name: "word joiner",
        replacement: "remove it",
    },
    Rule {
        codepoint: 0x2066,
        name: "left-to-right isolate control",
        replacement: "remove it or use an ASCII Unicode escape for intentional handling",
    },
    Rule {
        codepoint: 0x2067,
        name: "right-to-left isolate control",
        replacement: "remove it or use an ASCII Unicode escape for intentional handling",
    },
    Rule {
        codepoint: 0x2068,
        name: "first strong isolate control",
        replacement: "remove it or use an ASCII Unicode escape for intentional handling",
    },
    Rule {
        codepoint: 0x2069,
        name: "pop directional isolate control",
        replacement: "remove it or use an ASCII Unicode escape for intentional handling",
    },
    Rule {
        codepoint: 0x2212,
        name: "Unicode minus sign",
        replacement: "ASCII hyphen-minus (-)",
    },
    Rule {
        codepoint: 0xfeff,
        name: "byte order mark or zero-width no-break space",
        replacement: "remove it",
    },
];

#[derive(Debug, Eq, PartialEq)]
struct Finding {
    line: usize,
    column: usize,
    rule: Rule,
}

fn rule_for(character: char) -> Option<Rule> {
    let codepoint = character as u32;
    RULES
        .iter()
        .copied()
        .find(|rule| rule.codepoint == codepoint)
}

fn scan_text(text: &str) -> Vec<Finding> {
    let mut findings = Vec::new();
    let mut line = 1;
    let mut column = 1;

    for character in text.chars() {
        if let Some(rule) = rule_for(character) {
            findings.push(Finding { line, column, rule });
        }

        if character == '\n' {
            line += 1;
            column = 1;
        } else {
            column += 1;
        }
    }

    findings
}

fn read_null_delimited_paths() -> io::Result<Vec<PathBuf>> {
    let mut input = Vec::new();
    io::stdin().read_to_end(&mut input)?;

    input
        .split(|byte| *byte == 0)
        .filter(|path| !path.is_empty())
        .map(|path| {
            String::from_utf8(path.to_vec())
                .map(PathBuf::from)
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "path is not UTF-8"))
        })
        .collect()
}

fn scan_file(path: &Path) -> io::Result<Vec<Finding>> {
    let bytes = fs::read(path)?;

    if bytes.contains(&0) {
        return Ok(Vec::new());
    }

    let Ok(text) = std::str::from_utf8(&bytes) else {
        return Ok(Vec::new());
    };

    Ok(scan_text(text))
}

fn usage(program: &str) {
    eprintln!("Usage: {program} [--null] [FILE ...]");
    eprintln!("  --null  read NUL-delimited repository-relative paths from standard input");
}

fn run() -> Result<bool, String> {
    let mut arguments = env::args();
    let program = arguments
        .next()
        .unwrap_or_else(|| String::from("text-hygiene"));
    let mut read_from_stdin = false;
    let mut paths = Vec::new();

    for argument in arguments {
        match argument.as_str() {
            "--null" => read_from_stdin = true,
            "-h" | "--help" => {
                usage(&program);
                return Ok(false);
            }
            option if option.starts_with('-') => {
                return Err(format!("unknown option: {option}"));
            }
            path => paths.push(PathBuf::from(path)),
        }
    }

    if read_from_stdin {
        paths.extend(
            read_null_delimited_paths()
                .map_err(|error| format!("could not read paths from standard input: {error}"))?,
        );
    }

    if paths.is_empty() {
        usage(&program);
        return Err(String::from("no files were provided"));
    }

    let mut finding_count = 0usize;
    let mut read_error_count = 0usize;

    for path in paths {
        if !path.is_file() {
            continue;
        }

        match scan_file(&path) {
            Ok(findings) => {
                for finding in findings {
                    eprintln!(
                        "{}:{}:{}: {} U+{:04X}; use {}",
                        path.display(),
                        finding.line,
                        finding.column,
                        finding.rule.name,
                        finding.rule.codepoint,
                        finding.rule.replacement
                    );
                    finding_count += 1;
                }
            }
            Err(error) => {
                eprintln!("{}: could not read file: {error}", path.display());
                read_error_count += 1;
            }
        }
    }

    if finding_count > 0 {
        eprintln!(
            "Text hygiene check failed with {finding_count} forbidden character occurrence(s)."
        );
    }

    if read_error_count > 0 {
        eprintln!("Text hygiene check could not read {read_error_count} file(s).");
    }

    Ok(finding_count > 0 || read_error_count > 0)
}

fn main() -> ExitCode {
    match run() {
        Ok(true) => ExitCode::from(1),
        Ok(false) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("text-hygiene: {error}");
            ExitCode::from(2)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{scan_text, RULES};

    #[test]
    fn accepts_ascii_punctuation_and_normal_unicode_letters() {
        let findings = scan_text("Obiente: Zoe, Jose, Rene, and a regular \"quote\".\n");
        assert!(findings.is_empty());

        let findings = scan_text("Zo\u{00eb}, Jos\u{00e9}, and Ren\u{00e9} remain valid UTF-8.\n");
        assert!(findings.is_empty());
    }

    #[test]
    fn detects_every_configured_character() {
        let text: String = RULES
            .iter()
            .map(|rule| char::from_u32(rule.codepoint).expect("valid configured codepoint"))
            .collect();

        let findings = scan_text(&text);

        assert_eq!(findings.len(), RULES.len());
        for (finding, rule) in findings.iter().zip(RULES) {
            assert_eq!(finding.rule, *rule);
        }
    }

    #[test]
    fn reports_one_based_line_and_character_column() {
        let text = format!(
            "plain\nab{}c\n",
            char::from_u32(0x2026).expect("valid ellipsis codepoint")
        );

        let findings = scan_text(&text);

        assert_eq!(findings.len(), 1);
        assert_eq!(findings[0].line, 2);
        assert_eq!(findings[0].column, 3);
    }

    #[test]
    fn permits_unicode_characters_outside_the_targeted_rules() {
        let findings = scan_text(
            "Calendar: 2026-07-25. Greek: \u{03ba}\u{03cc}\u{03c3}\u{03bc}\u{03bf}\u{03c2}.\n",
        );
        assert!(findings.is_empty());
    }
}
