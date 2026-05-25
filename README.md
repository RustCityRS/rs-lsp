# rs-lsp

A Language Server (LSP) and JetBrains IDE plugin for **RuneScript 2** (`.rs2`) — built with
the [rs-runec](https://crates.io/crates/rs-runec) compiler.

## Features

### Syntax Highlighting

Full syntax-aware highlighting that follows your IDE's color theme:

| Element           | Color         | Example                                                  |
|-------------------|---------------|----------------------------------------------------------|
| Keywords          | Orange        | `if`, `else`, `while`, `return`, `switch_int`, `def_int` |
| Primitive types   | Orange        | `int`, `string`, `boolean`, `coord`, `namedobj`          |
| Commands          | Function name | `inv_del(...)`, `mes(...)`, `npc_coord`                  |
| Trigger headers   | Function name | `[proc,name]`, `[opnpc1,man]`                            |
| Proc/label calls  | Blue          | `~my_proc(...)`, `@my_label(...)`                        |
| Script names      | Blue          | `my_proc` in `[proc,my_proc]`                            |
| Entity references | Purple        | `bronze_axe`, `man`, `lumbridge`                         |
| Constants         | Purple italic | `^max_health`, `^quest_complete`                         |
| Game variables    | Purple italic | `%quest_progress`, `%combat_level`                       |
| Local variables   | Default       | `$count`, `$target_npc`                                  |
| Strings           | Green         | `"Hello, adventurer!"`                                   |
| Numbers / Coords  | Blue          | `42`, `0_50_50_10_10`                                    |
| Comments          | Gray          | `// this is a comment`                                   |

Entity references are resolved from `.pack` files — the highlighter knows that `bronze_axe` is an `obj`, `man` is an
`npc`, and `prayer` is a `stat`.

### Diagnostics

Real-time error checking powered by the [rs-runec](https://crates.io/crates/rs-runec) compiler:

**Fast pass** (instant, on every keystroke):

- Lexer errors (unterminated strings, invalid characters)
- Parser errors (missing brackets, unexpected tokens)
- Type checking (wrong argument types, unknown commands, unresolved procs)
- Trigger validation (unknown triggers, unresolved entity subjects)

**Slow pass** (debounced 500ms after typing stops):

- Unused local variables and parameters
- Unreachable code after `return` or `@jump`
- Cross-file pointer checking (stale `last_useitem` references, missing protected access)

```
                    ┌─────────────┐
   Keystroke ──────>│  Fast Pass  │──── Publish immediately
                    │ lex + parse │
                    │ + typecheck │
                    └─────────────┘
                           │
                     500ms delay
                           │
                    ┌─────────────┐
                    │  Slow Pass  │──── Publish (merged with fast)
                    │  compile +  │
                    │ lint + ptrs │
                    └─────────────┘
```

### Code Completion

Context-aware suggestions that auto-popup as you type:

| Context           | Suggestions                                      |
|-------------------|--------------------------------------------------|
| `~`               | All proc names across the project                |
| `@`               | All label names across the project               |
| `^`               | All constants from `.constant` files             |
| `%`               | All game variables (varp, varn, vars, varbit)    |
| `[` at line start | Trigger types (`proc`, `opnpc1`, `opheld1`, ...) |
| `[opnpc1,`        | Only `npc` entities                              |
| `[opheld1,`       | Only `obj` entities                              |
| `[proc,`          | Existing proc names                              |
| `def_`            | Type declarations (`def_int`, `def_obj`, ...)    |
| `inv_del(`        | 1st arg: only `inv` entities                     |
| `inv_del(inv, `   | 2nd arg: only `obj` entities                     |
| `~my_proc(`       | Filtered by the proc's declared parameter types  |
| `Ctrl+Space`      | Commands, entities, keywords                     |

Argument-position completions read parameter types from `engine.rs2` (for commands) and from `[proc,...]`/`[label,...]`
declarations (for scripts), then filter suggestions to only show entities of the expected type.

### Go to Definition

**Ctrl+click** any symbol to jump to its definition:

| Symbol                 | Navigates to                               |
|------------------------|--------------------------------------------|
| `~proc_name`           | `[proc,proc_name]` header in `.rs2` file   |
| `@label_name`          | `[label,label_name]` header in `.rs2` file |
| `inv_del`              | `[command,inv_del]` in `engine.rs2`        |
| `bronze_axe`           | Line in `obj.pack`                         |
| `^max_health`          | Definition in `.constant` file             |
| `shop_template:com_76` | Line in `interface.pack`                   |

### Find Usages

**Ctrl+click** a definition to find all references across the project. Whole-word matching ensures `inv` doesn't match
`inv_del`.

### Rename Refactoring

**Shift+F6** to rename any symbol across the entire project:

- **Procs/labels**: Updates `~name`, `@name`, and `[trigger,name]` across all `.rs2` files
- **Commands**: Updates all `.rs2` files + `command.pack`
- **Entities**: Updates all `.rs2` files + all `.pack` files
- **Constants**: Updates all `.rs2` files + `.constant` files
- **Game variables**: Updates `%name` references across all `.rs2` files
- **Local variables**: Updates `$name` within the current file

### Extract Proc

**Ctrl+Alt+M** to extract selected code into a new proc:

1. Select a block of code
2. Enter the new proc name
3. The plugin automatically:
    - Detects local variables used in the selection and their types
    - Generates `[proc,name](type $param, ...)` at the end of the file
    - Replaces the selection with `~proc_name($params);`

### Spellcheck Suppression

Typo warnings from the IDE's built-in spellchecker are suppressed for `.rs2` files. No more green squiggles on
`namedobj` or `opheldu`.

### Inspection Suppression

All non-LSP IDE inspections are suppressed for `.rs2` files. Only diagnostics from the RS2 language server are shown.

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                   JetBrains IDE                      │
│                                                      │
│  ┌────────────────────────────────────────────────┐  │
│  │              rs-lsp-plugin (Kotlin)            │  │
│  │                                                │  │
│  │  Rs2Lexer ──── Rs2SyntaxHighlighter            │  │
│  │  Rs2Annotator (commands + entities from .pack) │  │
│  │  Rs2CompletionContributor (context-aware)      │  │
│  │  Rs2GotoDeclarationHandler                     │  │
│  │  Rs2RenameHandler                              │  │
│  │  Rs2ExtractProcAction                          │  │
│  │                                                │  │
│  └──────────────────┬─────────────────────────────┘  │
│                     │ LSP (stdin/stdout)             │
│  ┌──────────────────▼─────────────────────────────┐  │
│  │              rs-lsp (Rust binary)              │  │
│  │                                                │  │
│  │  tower-lsp server                              │  │
│  │    ├── textDocument/didChange → diagnose()     │  │
│  │    ├── textDocument/didSave → re-register      │  │
│  │    └── textDocument/semanticTokens             │  │
│  │                                                │  │
│  │  rs-runec compiler crate                       │  │
│  │    ├── Lexer → Parser → TypeChecker            │  │
│  │    ├── Compiler → Lints                        │  │
│  │    └── PointerChecker (cross-file)             │  │
│  │                                                │  │
│  │  SymbolRegistry                                │  │
│  │    ├── command.pack → command signatures       │  │
│  │    ├── *.pack → entity IDs                     │  │
│  │    ├── engine.rs2 → command param types        │  │
│  │    ├── *.constant → constants                  │  │
│  │    └── *.rs2 → proc/label registrations        │  │
│  └────────────────────────────────────────────────┘  │
│                                                      │
│  ┌────────────────────────────────────────────────┐  │
│  │              Project (rs-server)               │  │
│  │  content/                                      │  │
│  │    ├── pack/ (*.pack files)                    │  │
│  │    └── scripts/ (*.rs2 files + engine.rs2)     │  │
│  └────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────┘
```

The plugin handles all IDE-facing features (highlighting, completions, navigation, refactoring) while the LSP binary
handles heavy analysis (type checking, linting, pointer checking) using
the [rs-runec](https://crates.io/crates/rs-runec) compiler as a library.

## Installation

### Prerequisites

- **Rust** 1.95+ (`rustup` recommended)
- **JDK** 21+ (for building the plugin)
- **RustRover** 2024.2+ (or any JetBrains IDE)

### Install the LSP binary

```bash
# From the repo
cargo install --path .

# Or from crates.io (when published)
cargo install rs-lsp
```

This places `rs-lsp` in `~/.cargo/bin/` where the plugin will find it automatically.

### Install the plugin

```bash
cd plugin
./gradlew buildPlugin
```

The built plugin ZIP is at `plugin/build/distributions/rs-lsp-plugin-*.zip`. Install it in your IDE via **Settings >
Plugins > Install Plugin from Disk**.

### Development

To test with a sandboxed IDE:

```bash
# Build the LSP binary
cargo build

# Launch sandboxed IDE with plugin loaded
cd plugin
./gradlew runIde
```

The plugin automatically finds the LSP binary at:

1. `$RS_LSP_PATH` environment variable
2. `~/.cargo/bin/rs-lsp`
3. `<project>/target/debug/rs-lsp` (when built from source)
4. `rs-lsp` on `PATH`
