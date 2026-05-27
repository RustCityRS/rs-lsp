# rs-lsp

A Language Server (LSP) and JetBrains IDE plugin for **RuneScript 2** (`.rs2`) — built with
the [rs-runec](https://crates.io/crates/rs-runec) compiler.

---

## Features

### Syntax Highlighting

Full syntax-aware highlighting that follows your IDE's color theme:

| Element              | Color         | Example                                                      |
|----------------------|---------------|--------------------------------------------------------------|
| Keywords             | Orange        | `if`, `else`, `while`, `return`, `switch_int`, `def_int`     |
| Primitive types      | Orange        | `int`, `string`, `boolean`, `coord`, `player_uid`, `npc_uid` |
| Trigger types        | Orange        | `proc`, `label`, `opheld1`, `opnpc1` in `[trigger,...]`      |
| Commands             | Function name | `inv_del(...)`, `mes(...)`, `npc_coord`, `uid`               |
| Trigger headers      | Function name | `[proc,name]`, `[opnpc1,man]`                                |
| Proc/label calls     | Blue          | `~my_proc(...)`, `@my_label(...)`                            |
| Script names         | Blue          | `my_proc` in `[proc,my_proc]`                                |
| Script refs          | Blue          | `fluffs_complete` in `queue(fluffs_complete, ...)`           |
| Script ref commands  | Blue          | First arg of `gosub()`, `jump()`, `settimer()`, etc.         |
| Entity references    | Purple        | `bronze_axe`, `man`, `lumbridge`, `premade_cheese+tom_batta` |
| Constants            | Purple italic | `^max_health`, `^quest_complete`                             |
| Game variables       | Purple italic | `%quest_progress`, `%combat_level`                           |
| Local variables      | Default       | `$count`, `$target_npc`                                      |
| Strings              | Green         | `"Hello, adventurer!"`                                       |
| String interpolation | Mixed         | `"<npc_name>"` — commands, vars, mesanims inside `<>`        |
| Numbers / Coords     | Blue          | `42`, `0_50_50_10_10`                                        |
| Comments             | Gray          | `// this is a comment`                                       |

#### Context-Aware Type Keywords

Types like `queue`, `timer`, `softtimer`, `walktrigger`, `npc_uid`, and `player_uid` are context-aware:

- Highlighted as **type** (orange) when followed by `$` — e.g. `timer $t`
- Highlighted as **command** when followed by `(` — e.g. `timer(name, 100)`
- Both `obj` and `namedobj` arguments resolve to `.obj` config files

#### String Interpolation

Strings are split at `<>` boundaries into separate tokens. Inside interpolation blocks:

```
"Defeated a <npc_name> and received a <oc_name($drop)>!"
            ^^^^^^^^^^                 ^^^^^^^^^^^^^^^
            command (highlighted)      command + $var (highlighted)

"<p,neutral>Hello"
 ^^^^^^^^^^
 mesanim tag (highlighted as keyword)
```

- `<command>` and `<command($var)>` — command name, parentheses, variables all individually highlighted
- `<p,neutral>` — mesanim tags highlighted as keywords, with Ctrl+click to `.mesanim` config
- `<` and `>` delimiters — highlighted in keyword color

Entity names containing `+` (like `premade_cheese+tom_batta`) are fully supported across highlighting,
navigation, and rename.

<img width="1200" height="760" alt="Screenshot_5" src="https://github.com/user-attachments/assets/dff093dd-7238-46cd-bc09-8be155860f10" />

---

### Hover Documentation

Mouse over any symbol to see its declaration or config in a tooltip:

| Symbol             | Shows                                                        |
|--------------------|--------------------------------------------------------------|
| `mes`              | `[command,mes](string $message)`                             |
| `~my_proc`         | `[proc,my_proc](int $arg)(int)` with params and return types |
| `$var`             | `def_int $var` declaration line (scoped to current proc)     |
| `%quest`           | `int %quest // varp` with type and category                  |
| `^max_health`      | `int ^max_health = 99` with type and value                   |
| `bronze_axe`       | Full config block from the `.obj` file                       |
| `bank`             | Config block from `.inv` file                                |
| `npc_name` in `<>` | Command signature from `engine.rs2`                          |

#### Type-Aware Entity Hover

Hover is context-aware — it checks the expected parameter type from the function signature:

```
inv_add(bank, bronze_axe, 1);
        ^^^^  ^^^^^^^^^^
        inv   obj          ← hover shows .inv config for bank, .obj config for bronze_axe
        
settimer(general_macro_events, 500);
         ^^^^^^^^^^^^^^^^^^^^
         timer                 ← hover shows [timer,general_macro_events] script, not an enum entity
```

Entity configs are read from `.obj`, `.npc`, `.inv`, `.loc`, and other config files in `content/scripts/`.
Both `obj` and `namedobj` types resolve to `.obj` config files. Interface entities use `.if` extension.

Hover is disabled inside string literals (except for interpolation tokens inside `<>`).

---

### Inlay Hints

Parameter name hints, return type hints, and constant type hints appear inline:

```
queue(queue: fluffs_complete, delay: 0, arg: 0)
~get_level(skill: attack) -> int
~get_zqtombdoorstate -> int                        ← zero-arg procs show return type too
%npc_aggressive_player = uid -> player_uid;        ← zero-arg commands show return type
%zombiequeen = ^zombiequeen_complete : int;        ← constants show their type
```

- **Parameter names** — shown for all command and proc calls (with or without parentheses)
- **Return types** — shown for procs and commands that return values
- **Constant types** — shown for `^constant` references (`int`, `string`, `coord`)
- Hints appear instantly on file open (plugin-native, no LSP startup delay)
- Hints are disabled for `engine.rs2` (declaration file, not usage)
- Hints skip string literals and comments

---

### Diagnostics

Real-time error checking powered by the [rs-runec](https://crates.io/crates/rs-runec) compiler.
Errors appear as you type — no save required.

**Fast pass** (instant, on every keystroke):

- Lexer errors (unterminated strings, invalid characters)
- Parser errors (missing brackets, unexpected tokens)
- Type checking (wrong argument types, unknown commands, unresolved procs)
- Trigger validation (unknown triggers reported as **errors**, unresolved entity subjects)

**Slow pass** (debounced 500ms after typing stops):

- Unused local variables and parameters
- Unreachable code after `return` or `@jump`
- Cross-file pointer checking (stale `last_useitem` references, missing protected access)

#### Live Cross-File Error Detection

When you rename or delete a proc definition, errors appear immediately in all open files that reference it —
without saving. The LSP tracks script definitions per-file and rebuilds the symbol registry when names change.

```
                    +-------------------+
   Keystroke ------>|  Fast Pass        |---- Publish immediately
                    | lex + parse       |
                    | + typecheck       |
                    | + registry update |
                    +-------------------+
                           |
                     500ms delay
                           |
                    +-------------------+
                    |  Slow Pass        |---- Publish (merged with fast)
                    |  compile + lint   |
                    |  + pointer check  |
                    +-------------------+
                           |
                    Re-diagnose all open files
```

<img width="1200" height="760" alt="Screenshot_8" src="https://github.com/user-attachments/assets/9003ef88-97a6-49f7-bcbc-0cfc9f7eb756" />

---

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

<img width="1200" height="760" alt="Screenshot_7" src="https://github.com/user-attachments/assets/0ee40108-3eb2-48cd-b3b8-dccc421c80e9" />

---

### Go to Definition

**Ctrl+click** any symbol to jump to its definition:

| Symbol                   | Navigates to                                               |
|--------------------------|------------------------------------------------------------|
| `~proc_name`             | `[proc,proc_name]` header in `.rs2` file                   |
| `@label_name`            | `[label,label_name]` header in `.rs2` file                 |
| `$var`                   | `def_type $var` declaration (scoped to current proc/label) |
| `$param`                 | Parameter in `[proc,name](type $param)` header             |
| `inv_del`                | `[command,inv_del]` in `engine.rs2`                        |
| `bronze_axe`             | `[bronze_axe]` in `.obj` config file                       |
| `questlist:chompybird`   | Config entry (both sides of `:` navigate)                  |
| `^max_health`            | Definition in `.constant` file                             |
| `%quest_var`             | Definition in `.varp`/`.varn`/`.vars`/`.varbit` config     |
| `fluffs_complete`        | `[queue,fluffs_complete]` header (script ref)              |
| `npc_death` in `gosub()` | `[proc,npc_death]` header                                  |
| `<npc_name>` in string   | `[command,npc_name]` in `engine.rs2`                       |
| `<$drop>` in string      | `def_type $drop` declaration                               |
| `<p,neutral>` in string  | `[p,neutral]` in `.mesanim` config file                    |

#### Scope-Aware Local Variables

Clicking `$rand` in one proc jumps to its `def_int $rand` in **that proc**, not to a same-named variable in a
different proc. The search is bounded by trigger headers (`[proc,...]`, `[label,...]`, etc.).

Parameters in trigger headers are also resolved: `$param` in `[proc,name](type $param)`.

#### Entity Config Navigation

Entities navigate to their **config file** (`.obj`, `.npc`, `.inv`, etc.) instead of the `.pack` file:

- `obj` and `namedobj` types both resolve to `.obj` config files
- `interface` entities resolve to `.if` config files
- Qualified references like `questlist:chompybird` are treated as one entity name
- Entity names with `+` (like `premade_cheese+tom_batta`) are fully supported
- Falls back to the `.pack` file entry at the exact line if no config file exists

---

### Find Usages

**Ctrl+click** a proc/label declaration to see all call sites in a rich popup with file names, line numbers, and code
context. Uses IntelliJ's native Find Usages infrastructure:

- **Ctrl+click** on `[proc,my_proc]` or `[label,my_label]` — shows rich usages popup directly
- **Alt+F7** on any symbol — opens the Find Usages tool window
- **Ctrl+Alt+F7** — shows the Show Usages popup

The rich popup shows all `~name` and `@name` call sites across `content/scripts/`, grouped by file with code previews.

<img width="1200" height="760" alt="Screenshot_9" src="https://github.com/user-attachments/assets/64a85406-2d8a-4cc9-bd77-faa09df6645c" />

---

### Rename Refactoring

**Shift+F6** to rename any symbol across the entire project:

| Symbol type         | Updates                                                                     |
|---------------------|-----------------------------------------------------------------------------|
| **Procs/labels**    | `~name`, `@name`, `[trigger,name]` across all `.rs2` files                  |
| **Commands**        | All `.rs2` files + `command.pack`                                           |
| **Entities**        | Config files, `.pack` files, trigger headers, and code usages               |
| **Constants**       | All `.rs2` files + `.constant` files                                        |
| **Game variables**  | `%name` in `.rs2` files + `.varp`/`.varn`/`.vars`/`.varbit` configs + packs |
| **Local variables** | `$name` within the current file                                             |

#### Smart Rename Scoping

- Entity rename **skips** `[proc,...]`, `[label,...]`, and `[debugproc,...]` trigger headers to avoid false matches
- Entity rename **skips** string literals
- Primitive types, keywords, and operators cannot be renamed
- Works correctly with text selection (uses selection start, not caret end)
- Modified files are saved and editors refreshed after rename

<img width="505" height="364" alt="Screenshot_10" src="https://github.com/user-attachments/assets/b3c5a7ee-ee25-4f23-8c69-8f9ba3c84eb1" />

---

### Extract Proc

**Ctrl+Alt+M** to extract selected code into a new proc:

1. Select a block of code
2. Enter the new proc name
3. The plugin automatically:
    - Detects local variables used in the selection and their types
    - Generates `[proc,name](type $param, ...)` at the end of the file
    - Replaces the selection with `~proc_name($params);`

---

### Bundled LSP Binary

The LSP binary is bundled inside the plugin JAR and automatically extracted on first run. No separate installation
required — installing the plugin from the JetBrains Marketplace gives you everything.

- Platform-specific binaries (`x86_64-pc-windows-msvc`, `aarch64-apple-darwin`, etc.)
- Automatic extraction to the plugins directory
- LSP starts automatically when a project with `.rs2` files is opened
- LSP stops when the IDE closes (with process cleanup on project close)

---

### Additional Features

- **Spellcheck suppression** — typo warnings from the IDE's built-in spellchecker are suppressed for `.rs2` files
- **Inspection suppression** — all non-LSP IDE inspections are suppressed; only RS2 diagnostics are shown
- **VFS listener** — external file changes (git checkout, rollback) trigger LSP restart and highlighting refresh
- **IDE restart prompt** — plugin installation prompts for IDE restart via `require-restart="true"`

---

## Architecture

```
+------------------------------------------------------------------+
|                        JetBrains IDE                             |
|                                                                  |
|  +------------------------------------------------------------+  |
|  |                  rs-lsp-plugin (Kotlin)                    |  |
|  |                                                            |  |
|  |  Lexer & Highlighting                                      |  |
|  |    Rs2Lexer (string interpolation splitting)               |  |
|  |    Rs2SyntaxHighlighter (token -> color mapping)           |  |
|  |    Rs2Annotator (commands, entities, string <> tags)       |  |
|  |                                                            |  |
|  |  Navigation & Usages                                       |  |
|  |    Rs2GotoDeclarationHandler (definition lookup)           |  |
|  |    Rs2GotoDeclarationWrapper (Ctrl+Click -> Show Usages)   |  |
|  |    Rs2FindUsagesHandlerFactory (rich usages popup)         |  |
|  |                                                            |  |
|  |  Code Intelligence                                         |  |
|  |    Rs2InlayHintsProvider (param names, return types)       |  |
|  |    Rs2CompletionContributor (context-aware completions)    |  |
|  |    Rs2CommandRegistry (pack/script/param index)            |  |
|  |                                                            |  |
|  |  Refactoring                                               |  |
|  |    Rs2RenameHandler (multi-file, scope-aware)              |  |
|  |    Rs2ExtractProcAction (selection -> new proc)            |  |
|  |                                                            |  |
|  |  Infrastructure                                            |  |
|  |    Rs2IconProvider (file type icons)                       |  |
|  |    Rs2LspBinaryManager (bundled binary extraction)         |  |
|  |    Rs2VfsListener (external change detection)              |  |
|  |    Rs2ProjectCloseListener (LSP cleanup)                   |  |
|  |                                                            |  |
|  +----------------------------+-------------------------------+  |
|                               | LSP (stdin/stdout)               |
|  +----------------------------v-------------------------------+  |
|  |                  rs-lsp (Rust binary)                      |  |
|  |                                                            |  |
|  |  tower-lsp server                                          |  |
|  |    +-- textDocument/didOpen -----> diagnose (fast pass)    |  |
|  |    +-- textDocument/didChange ---> diagnose + registry     |  |
|  |    |                               update + cross-file     |  |
|  |    +-- textDocument/didSave -----> re-index + full pass    |  |
|  |    +-- textDocument/definition --> scope-aware lookup      |  |
|  |    +-- textDocument/references --> whole-word search       |  |
|  |    +-- textDocument/hover -------> type-aware entity hover |  |
|  |    +-- textDocument/semanticTokens -> command highlighting |  |
|  |                                                            |  |
|  |  rs-runec compiler crate                                   |  |
|  |    +-- Lexer -> Parser -> TypeChecker                      |  |
|  |    +-- Compiler -> Lints (unused vars, unreachable code)   |  |
|  |    +-- PointerChecker (cross-file validation)              |  |
|  |                                                            |  |
|  |  SymbolRegistry (live, rebuilt on script changes)          |  |
|  |    +-- base registry: packs, constants, commands           |  |
|  |    +-- script overlay: proc/label registrations            |  |
|  |    +-- per-file tracking for incremental updates           |  |
|  +------------------------------------------------------------+  |
|                                                                  |
|  +------------------------------------------------------------+  |
|  |  Project layout                                            |  |
|  |  content/                                                  |  |
|  |    +-- pack/ (*.pack entity/command indices)               |  |
|  |    +-- scripts/                                            |  |
|  |         +-- *.rs2 (RuneScript source files)                |  |
|  |         +-- engine.rs2 (command definitions)               |  |
|  |         +-- *.obj, *.npc, *.inv, ... (entity configs)      |  |
|  |         +-- *.constant (constant definitions)              |  |
|  |         +-- *.varp, *.varn, ... (game variable configs)    |  |
|  +------------------------------------------------------------+  |
+------------------------------------------------------------------+
```

---

### Development

To test with a sandboxed IDE:

```bash
# Build the LSP binary
cargo build --release

# Copy to plugin resources (adjust target triple for your OS)
cp target/release/rs-lsp plugin/src/main/resources/bin/rs-lsp-x86_64-pc-windows-msvc.exe

# Launch sandboxed IDE with plugin loaded
cd plugin
./gradlew runIde
```

The plugin automatically finds the LSP binary at:

1. Bundled binary extracted from plugin JAR (primary)
2. `$RS_LSP_PATH` environment variable
3. `~/.cargo/bin/rs-lsp`
4. `<project>/target/debug/rs-lsp` (when built from source)
5. `rs-lsp` on `PATH`
