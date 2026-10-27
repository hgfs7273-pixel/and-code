package com.yugahashimoto.andcode.feature.chat

/**
 * Deps-free tokenizer for fenced code blocks rendered in chat. It converts source text into spans
 * tagged with a [CodeTokenType] so composables can apply per-theme colors without pulling in a
 * full third-party highlighter. Concatenating the token texts always reproduces the input
 * exactly, so the raw code stays intact.
 */
enum class CodeTokenType {
    Plain,
    Keyword,
    String,
    Number,
    Comment,
    Function,
    DiffAdd,
    DiffRemove,
    DiffMeta,
}

/** One self-contained span of highlighted code; text never crosses token boundaries. */
data class CodeToken(val text: String, val type: CodeTokenType)

object CodeHighlighter {
    private const val MAX_HIGHLIGHT_LENGTH = 20_000

    private val DIFF_LANGUAGES = setOf("diff", "patch")

    private val ALIASES =
        mapOf(
            "py" to "python",
            "python3" to "python",
            "js" to "javascript",
            "jsx" to "javascript",
            "javascriptreact" to "javascript",
            "mjs" to "javascript",
            "cjs" to "javascript",
            "node" to "javascript",
            "ts" to "typescript",
            "tsx" to "typescript",
            "typescriptreact" to "typescript",
            "mts" to "typescript",
            "cts" to "typescript",
            "kt" to "kotlin",
            "kts" to "kotlin",
            "c++" to "cpp",
            "cc" to "cpp",
            "cxx" to "cpp",
            "cplusplus" to "cpp",
            "hpp" to "cpp",
            "h" to "c",
            "objc" to "c",
            "objective-c" to "c",
            "objectivec" to "c",
            "cs" to "csharp",
            "c#" to "csharp",
            "golang" to "go",
            "rs" to "rust",
            "sh" to "shell",
            "bash" to "shell",
            "zsh" to "shell",
            "fish" to "shell",
            "shellscript" to "shell",
            "shell-session" to "shell",
            "console" to "shell",
            "terminal" to "shell",
            "json5" to "json",
            "jsonc" to "json",
            "yml" to "yaml",
            "patch" to "diff",
        )

    fun highlight(language: String?, code: String): List<CodeToken> {
        if (code.length > MAX_HIGHLIGHT_LENGTH) {
            return listOf(CodeToken(code, CodeTokenType.Plain))
        }
        val lang =
            language
                ?.substringBefore(':')
                ?.substringBefore(' ')
                ?.trim()
                ?.lowercase()
                .orEmpty()
        return when {
            lang in DIFF_LANGUAGES -> highlightDiff(code)
            lang.isEmpty() -> tokenize(code, UNKNOWN_CONFIG)
            else -> {
                val canonical = ALIASES[lang] ?: lang
                val config = CONFIGS[canonical] ?: UNKNOWN_CONFIG
                tokenize(code, config)
            }
        }
    }

    private fun highlightDiff(code: String): List<CodeToken> {
        val tokens = mutableListOf<CodeToken>()
        val lines = code.split('\n')
        lines.forEachIndexed { index, line ->
            tokens += CodeToken(line, diffLineType(line))
            if (index < lines.lastIndex) {
                tokens += CodeToken("\n", CodeTokenType.Plain)
            }
        }
        return tokens
    }

    private fun diffLineType(line: String): CodeTokenType =
        when {
            line.startsWith("@@") -> CodeTokenType.DiffMeta
            line.startsWith("---") || line.startsWith("+++") -> CodeTokenType.DiffMeta
            line.startsWith("diff --git") ||
                line.startsWith("index ") ||
                line.startsWith("new file") ||
                line.startsWith("deleted file") ||
                line.startsWith("old mode") ||
                line.startsWith("new mode") ||
                line.startsWith("rename ") ||
                line.startsWith("similarity ") ||
                line.startsWith("Binary file") -> CodeTokenType.DiffMeta
            line.startsWith("+") -> CodeTokenType.DiffAdd
            line.startsWith("-") -> CodeTokenType.DiffRemove
            else -> CodeTokenType.Plain
        }

    private fun tokenize(code: String, config: LangConfig): List<CodeToken> {
        val tokens = mutableListOf<CodeToken>()
        val plain = StringBuilder()
        var i = 0
        val n = code.length

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                tokens += CodeToken(plain.toString(), CodeTokenType.Plain)
                plain.clear()
            }
        }

        while (i < n) {
            val char = code[i]

            val blockStart = config.blockCommentStart
            if (blockStart != null && code.startsWith(blockStart, i)) {
                flushPlain()
                val endDelim = config.blockCommentEnd
                val stop =
                    if (endDelim == null) {
                        n
                    } else {
                        val idx = code.indexOf(endDelim, i + blockStart.length)
                        if (idx == -1) n else idx + endDelim.length
                    }
                tokens += CodeToken(code.substring(i, stop), CodeTokenType.Comment)
                i = stop
                continue
            }

            val lineComment =
                config.lineComments.firstOrNull { prefix ->
                    code.startsWith(prefix, i) &&
                        (!config.lineCommentNeedsBoundary || i == 0 || code[i - 1].isWhitespace())
                }
            if (lineComment != null) {
                flushPlain()
                val stop = code.indexOf('\n', i)
                val end = if (stop == -1) n else stop
                tokens += CodeToken(code.substring(i, end), CodeTokenType.Comment)
                i = end
                continue
            }

            if (config.tripleQuotes && (code.startsWith("\"\"\"", i) || code.startsWith("'''", i))) {
                flushPlain()
                val delim = code.substring(i, i + 3)
                val idx = code.indexOf(delim, i + 3)
                val stop = if (idx == -1) n else idx + 3
                tokens += CodeToken(code.substring(i, stop), CodeTokenType.String)
                i = stop
                continue
            }

            val isTemplate = config.templateQuotes && char == '`'
            if (char in config.stringQuotes || isTemplate) {
                flushPlain()
                var j = i + 1
                while (j < n) {
                    when {
                        code[j] == '\\' && j + 1 < n -> j += 2
                        code[j] == char -> {
                            j++
                            break
                        }
                        else -> j++
                    }
                }
                j = j.coerceAtMost(n)
                // In key-value formats (JSON/YAML/TOML) a quoted string followed by ':' is a key.
                val type =
                    if (config.keyColonAware && nextNonSpaceOnLine(code, j) == ':') {
                        CodeTokenType.Keyword
                    } else {
                        CodeTokenType.String
                    }
                tokens += CodeToken(code.substring(i, j), type)
                i = j
                continue
            }

            if (char.isDigit() && (i == 0 || !isIdentifierChar(code[i - 1]))) {
                flushPlain()
                var j = i
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '.')) j++
                tokens += CodeToken(code.substring(i, j), CodeTokenType.Number)
                i = j
                continue
            }

            if (char.isLetter() || char == '_' || char == '$') {
                var j = i
                while (j < n && isIdentifierChar(code[j])) j++
                val word = code.substring(i, j)
                val type = resolveWordType(word, code, j, config)
                if (type == null) {
                    plain.append(word)
                } else {
                    flushPlain()
                    tokens += CodeToken(word, type)
                }
                i = j
                continue
            }

            plain.append(char)
            i++
        }
        flushPlain()
        return tokens
    }

    private fun resolveWordType(
        word: String,
        code: String,
        end: Int,
        config: LangConfig,
    ): CodeTokenType? {
        val isKeyword =
            if (config.caseInsensitiveKeywords) {
                config.keywords.any { it.equals(word, ignoreCase = true) }
            } else {
                word in config.keywords
            }
        if (isKeyword) return CodeTokenType.Keyword
        if (config.keyColonAware && nextNonSpaceOnLine(code, end) == ':') {
            return CodeTokenType.Keyword
        }
        if (code.getOrNull(end) == '(') return CodeTokenType.Function
        return null
    }

    private fun nextNonSpaceOnLine(code: String, from: Int): Char? {
        var j = from
        while (j < code.length && (code[j] == ' ' || code[j] == '\t')) j++
        return if (j < code.length && code[j] != '\n') code[j] else null
    }

    private fun isIdentifierChar(char: Char): Boolean =
        char.isLetterOrDigit() || char == '_' || char == '$'

    private data class LangConfig(
        val keywords: Set<String> = emptySet(),
        val lineComments: List<String> = emptyList(),
        val lineCommentNeedsBoundary: Boolean = false,
        val blockCommentStart: String? = null,
        val blockCommentEnd: String? = null,
        val stringQuotes: List<Char> = listOf('"', '\''),
        val templateQuotes: Boolean = false,
        val tripleQuotes: Boolean = false,
        val caseInsensitiveKeywords: Boolean = false,
        val keyColonAware: Boolean = false,
    )

    private val PYTHON_KEYWORDS =
        setOf(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
            "continue", "def", "del", "dict", "elif", "else", "except", "finally", "for", "from",
            "global", "if", "import", "in", "is", "lambda", "len", "list", "nonlocal", "not", "or",
            "pass", "print", "raise", "range", "return", "self", "set", "str", "tuple", "try",
            "while", "with", "yield",
        )

    private val JAVASCRIPT_KEYWORDS =
        setOf(
            "abstract", "as", "async", "await", "break", "case", "catch", "class", "const",
            "continue", "debugger", "declare", "default", "delete", "do", "else", "enum", "export",
            "extends", "false", "finally", "for", "function", "if", "implements", "import", "in",
            "instanceof", "interface", "is", "keyof", "let", "namespace", "new", "null", "of",
            "private", "protected", "public", "readonly", "return", "satisfies", "static", "super",
            "switch", "this", "throw", "true", "try", "type", "typeof", "undefined", "var", "void",
            "while", "yield",
        )

    private val KOTLIN_KEYWORDS =
        setOf(
            "abstract", "actual", "annotation", "as", "break", "by", "catch", "class", "companion",
            "const", "constructor", "continue", "crossinline", "data", "do", "dynamic", "else",
            "enum", "expect", "external", "false", "field", "final", "finally", "for", "fun", "get",
            "if", "import", "in", "infix", "init", "inline", "inner", "interface", "internal", "is",
            "lateinit", "noinline", "null", "object", "open", "operator", "out", "override",
            "package", "param", "private", "property", "protected", "public", "reified", "return",
            "sealed", "set", "setparam", "suspend", "tailrec", "this", "throw", "true", "try",
            "typealias", "val", "var", "vararg", "when", "where", "while",
        )

    private val JAVA_KEYWORDS =
        setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "false",
            "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
            "int", "interface", "long", "native", "new", "null", "package", "private", "protected",
            "public", "record", "return", "short", "static", "strictfp", "super", "switch",
            "synchronized", "this", "throw", "throws", "transient", "true", "try", "var", "void",
            "volatile", "while",
        )

    private val C_FAMILY_KEYWORDS =
        setOf(
            "auto", "base", "bool", "break", "case", "catch", "char", "class", "const", "consteval",
            "constexpr", "continue", "default", "delete", "do", "double", "else", "enum", "explicit",
            "extern", "false", "final", "float", "for", "friend", "goto", "if", "inline", "int",
            "internal", "long", "mutable", "namespace", "new", "noexcept", "null", "nullptr",
            "operator", "override", "private", "protected", "public", "readonly", "ref", "register",
            "return", "sealed", "short", "signed", "sizeof", "static", "string", "struct", "switch",
            "template", "this", "throw", "throws", "true", "try", "typedef", "typename", "uint",
            "union", "unsigned", "using", "virtual", "void", "volatile", "while",
        )

    private val GO_KEYWORDS =
        setOf(
            "append", "bool", "break", "byte", "cap", "case", "chan", "const", "continue",
            "default", "defer", "else", "error", "false", "fallthrough", "float32", "float64",
            "for", "func", "go", "goto", "if", "import", "int", "int16", "int32", "int64", "int8",
            "interface", "len", "make", "map", "new", "nil", "package", "panic", "print", "println",
            "range", "recover", "return", "rune", "select", "string", "struct", "switch", "true",
            "type", "uint", "uint16", "uint32", "uint64", "uint8", "var",
        )

    private val RUST_KEYWORDS =
        setOf(
            "as", "async", "await", "bool", "break", "char", "const", "continue", "crate", "dyn",
            "else", "enum", "Err", "extern", "false", "f32", "f64", "fn", "for", "i128", "i16",
            "i32", "i64", "i8", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut",
            "None", "Ok", "pub", "ref", "return", "self", "Self", "Some", "static", "str", "String",
            "struct", "super", "trait", "true", "type", "u128", "u16", "u32", "u64", "u8", "unsafe",
            "use", "Vec", "where", "while",
        )

    private val SWIFT_KEYWORDS =
        setOf(
            "actor", "as", "async", "await", "break", "case", "catch", "class", "continue",
            "default", "defer", "deinit", "do", "else", "enum", "extension", "false", "final",
            "for", "func", "get", "guard", "if", "import", "init", "inout", "internal", "is",
            "lazy", "let", "nil", "open", "operator", "private", "protocol", "public", "repeat",
            "return", "self", "set", "static", "struct", "subscript", "super", "switch", "throw",
            "throws", "true", "try", "typealias", "var", "where", "while", "willSet", "didSet",
        )

    private val SHELL_KEYWORDS =
        setOf(
            "if", "then", "elif", "else", "fi", "for", "while", "until", "do", "done", "in",
            "case", "esac", "function", "return", "break", "continue", "local", "export",
            "readonly", "set", "unset", "shift", "eval", "exit", "trap", "source", "alias",
            "declare", "test", "sudo", "cd", "echo", "printf", "ls", "cat", "grep", "sed", "awk",
            "find", "xargs", "rm", "cp", "mv", "mkdir", "touch", "chmod", "chown", "kill", "curl",
            "wget", "git", "npm", "npx", "yarn", "pip", "python", "python3", "node", "docker",
            "make", "bash", "sh", "tar", "zip", "unzip", "env", "true", "false", "which", "whoami",
            "pwd", "head", "tail", "wc", "sort", "uniq", "tee",
        )

    private val SQL_KEYWORDS =
        setOf(
            "select", "from", "where", "insert", "into", "values", "update", "set", "delete",
            "create", "table", "drop", "alter", "add", "column", "join", "inner", "left", "right",
            "full", "outer", "on", "group", "order", "by", "having", "limit", "offset", "as",
            "and", "or", "not", "null", "is", "in", "between", "like", "distinct", "union", "all",
            "exists", "primary", "foreign", "key", "references", "default", "unique", "index",
            "view", "case", "when", "then", "else", "end", "asc", "desc", "with", "begin",
            "commit", "rollback", "count", "avg", "sum", "min", "max", "int", "varchar", "text",
            "boolean", "date", "timestamp", "float",
        )

    private val UNKNOWN_CONFIG =
        LangConfig(
            keywords = emptySet(),
            lineComments = emptyList(),
            blockCommentStart = null,
            blockCommentEnd = null,
            stringQuotes = listOf('"', '\''),
        )

    private val CONFIGS: Map<String, LangConfig> =
        mapOf(
            "python" to
                LangConfig(
                    keywords = PYTHON_KEYWORDS,
                    lineComments = listOf("#"),
                    lineCommentNeedsBoundary = false,
                    tripleQuotes = true,
                ),
            "javascript" to
                LangConfig(
                    keywords = JAVASCRIPT_KEYWORDS,
                    lineComments = listOf("//"),
                    blockCommentStart = "/*",
                    blockCommentEnd = "*/",
                    templateQuotes = true,
                ),
            "typescript" to
                LangConfig(
                    keywords = JAVASCRIPT_KEYWORDS,
                    lineComments = listOf("//"),
                    blockCommentStart = "/*",
                    blockCommentEnd = "*/",
                    templateQuotes = true,
                ),
            "kotlin" to
                LangConfig(
                    keywords = KOTLIN_KEYWORDS,
                    lineComments = listOf("//"),
                    blockCommentStart = "/*",
                    blockCommentEnd = "*/",
                    templateQuotes = true,
                ),
            "java" to
                LangConfig(
                    keywords = JAVA_KEYWORDS,
                    lineComments = listOf("//"),
                    blockCommentStart = "/*",
                    blockCommentEnd = "*/",
                ),
            "c" to
                LangConfig(
                    keywords = C_FAMILY_KEYWORDS,
                    lineComments = listOf("//"),
                    blockCommentStart = "/*",
                    blockCommentEnd = "*/",
                ),
            "cpp" to
                LangConfig(
                    keywords = C_FAMILY_KEYWORDS,
                    lineComments = listOf("//"),
                    blockCommentStart = "/*",
                    blockCommentEnd = "*/",
                ),
            "csharp" to
                LangConfig(
                    keywords = C_FAMILY_KEYWORDS,
                    lineComments = listOf("//"),
                    blockCommentStart = "/*",
                    blockCommentEnd = "*/",
                ),
            "go" to
                LangConfig(
                    keywords = GO_KEYWORDS,
                    lineComments = listOf("//"),
                    blockCommentStart = "/*",
                    blockCommentEnd = "*/",
                ),
            "rust" to
                LangConfig(
                    keywords = RUST_KEYWORDS,
                    lineComments = listOf("//"),
                    blockCommentStart = "/*",
                    blockCommentEnd = "*/",
                ),
            "swift" to
                LangConfig(
                    keywords = SWIFT_KEYWORDS,
                    lineComments = listOf("//"),
                    blockCommentStart = "/*",
                    blockCommentEnd = "*/",
                ),
            "shell" to
                LangConfig(
                    keywords = SHELL_KEYWORDS,
                    lineComments = listOf("#"),
                    lineCommentNeedsBoundary = true,
                    stringQuotes = listOf('"'),
                ),
            "json" to
                LangConfig(
                    keywords = setOf("true", "false", "null"),
                    lineComments = listOf("//"),
                    stringQuotes = listOf('"'),
                    keyColonAware = true,
                ),
            "yaml" to
                LangConfig(
                    keywords = setOf("true", "false", "null", "yes", "no", "on", "off"),
                    lineComments = listOf("#"),
                    lineCommentNeedsBoundary = true,
                    keyColonAware = true,
                ),
            "toml" to
                LangConfig(
                    keywords = setOf("true", "false"),
                    lineComments = listOf("#"),
                    lineCommentNeedsBoundary = true,
                    keyColonAware = true,
                ),
            "sql" to
                LangConfig(
                    keywords = SQL_KEYWORDS,
                    lineComments = listOf("--"),
                    blockCommentStart = "/*",
                    blockCommentEnd = "*/",
                    caseInsensitiveKeywords = true,
                    stringQuotes = listOf('\''),
                ),
        )
}
