use std::path::{Path, PathBuf};
use std::sync::Arc;

use dashmap::DashMap;
use tokio::sync::RwLock;
use tower_lsp::jsonrpc::Result;
use tower_lsp::lsp_types::*;
use tower_lsp::{Client, LanguageServer, LspService, Server};
use tracing::info;

use runec::bytecode::CompiledScript;
use runec::compiler::Compiler;
use runec::diagnostics::Severity;
use runec::lexer::Lexer;
use runec::lints;
use runec::parser::Parser;
use runec::pointer_checker::PointerChecker;
use runec::symbol::SymbolRegistry;
use runec::symloader;
use runec::token::Kind as TokenKind;
use runec::typechecker::TypeChecker;

const TOKEN_TYPES: &[SemanticTokenType] = &[
    SemanticTokenType::FUNCTION,      // 0 - commands
    SemanticTokenType::VARIABLE,      // 1 - local vars
    SemanticTokenType::PROPERTY,      // 2 - game vars
    SemanticTokenType::STRING,        // 3 - constants
    SemanticTokenType::MACRO,         // 4 - proc/jump calls
    SemanticTokenType::KEYWORD,       // 5 - keywords
    SemanticTokenType::TYPE,          // 6 - type names
    SemanticTokenType::COMMENT,       // 7 - comments
    SemanticTokenType::NUMBER,        // 8 - numbers
    SemanticTokenType::new("string"), // 9 - strings
];

const TT_COMMAND: u32 = 0;
const TT_PROC: u32 = 4;

use std::sync::atomic::{AtomicU64, Ordering};

#[derive(Debug, Clone)]
struct DefinitionLocation {
    path: PathBuf,
    line: u32,
}

type DefIndex = std::collections::HashMap<String, DefinitionLocation>;

type ParamNameIndex = std::collections::HashMap<String, Vec<String>>;
type FileScriptsIndex = std::collections::HashMap<String, Vec<String>>;

struct Backend {
    client: Client,
    documents: Arc<DashMap<Url, String>>,
    registry: Arc<RwLock<Option<SymbolRegistry>>>,
    base_registry: Arc<RwLock<Option<SymbolRegistry>>>,
    scripts_dir: Arc<RwLock<Option<PathBuf>>>,
    all_compiled: Arc<RwLock<Vec<CompiledScript>>>,
    all_sources: Arc<RwLock<std::collections::HashMap<String, String>>>,
    definitions: Arc<RwLock<DefIndex>>,
    param_names: Arc<RwLock<ParamNameIndex>>,
    file_scripts: Arc<RwLock<FileScriptsIndex>>,
    change_version: Arc<AtomicU64>,
    fast_diagnostics: Arc<DashMap<Url, Vec<Diagnostic>>>,
}

impl Backend {
    fn new(client: Client) -> Self {
        Self {
            client,
            documents: Arc::new(DashMap::new()),
            registry: Arc::new(RwLock::new(None)),
            base_registry: Arc::new(RwLock::new(None)),
            scripts_dir: Arc::new(RwLock::new(None)),
            all_compiled: Arc::new(RwLock::new(Vec::new())),
            all_sources: Arc::new(RwLock::new(std::collections::HashMap::new())),
            definitions: Arc::new(RwLock::new(std::collections::HashMap::new())),
            param_names: Arc::new(RwLock::new(std::collections::HashMap::new())),
            file_scripts: Arc::new(RwLock::new(std::collections::HashMap::new())),
            change_version: Arc::new(AtomicU64::new(0)),
            fast_diagnostics: Arc::new(DashMap::new()),
        }
    }

    async fn find_script_call_sites(&self, name: &str) -> Vec<Location> {
        let sources = self.all_sources.read().await;
        let mut locations = Vec::new();
        let patterns = [format!("~{}", name), format!("@{}", name)];
        for (path_str, source) in sources.iter() {
            let path = PathBuf::from(path_str);
            let Ok(file_uri) = Url::from_file_path(&path) else {
                continue;
            };
            for (line_num, line) in source.lines().enumerate() {
                for pattern in &patterns {
                    let mut start = 0;
                    while let Some(idx) = line[start..].find(pattern.as_str()) {
                        let col = start + idx;
                        let after_idx = col + pattern.len();
                        let after_ok = after_idx >= line.len()
                            || !line.as_bytes()[after_idx].is_ascii_alphanumeric()
                                && line.as_bytes()[after_idx] != b'_';
                        if after_ok {
                            locations.push(Location {
                                uri: file_uri.clone(),
                                range: Range {
                                    start: Position {
                                        line: line_num as u32,
                                        character: col as u32,
                                    },
                                    end: Position {
                                        line: line_num as u32,
                                        character: (col + pattern.len()) as u32,
                                    },
                                },
                            });
                        }
                        start = col + pattern.len();
                    }
                }
            }
        }
        locations
    }

    async fn diagnose(&self, uri: &Url, text: &str) {
        let version = self.change_version.fetch_add(1, Ordering::SeqCst) + 1;
        let file_path = uri_to_path(uri);
        let mut lsp_diagnostics = Vec::new();

        // === FAST PASS: lex + parse + type check (immediate) ===

        // Lex
        let tokens = {
            let mut lexer = Lexer::new(text, &file_path);
            match lexer.tokenize() {
                Ok(tokens) => tokens,
                Err(e) => {
                    lsp_diagnostics.push(make_diagnostic_with_source(
                        e.line,
                        e.position,
                        &e.message,
                        DiagnosticSeverity::ERROR,
                        text,
                    ));
                    self.client
                        .publish_diagnostics(uri.clone(), lsp_diagnostics, None)
                        .await;
                    return;
                }
            }
        };

        // Parse
        let mut parser = Parser::new(tokens, &file_path);
        let file = match parser.parse() {
            Ok(f) => f,
            Err(e) => {
                lsp_diagnostics.push(make_diagnostic_with_source(
                    e.line,
                    e.position,
                    &e.message,
                    DiagnosticSeverity::ERROR,
                    text,
                ));
                self.client
                    .publish_diagnostics(uri.clone(), lsp_diagnostics, None)
                    .await;
                return;
            }
        };

        // Track script names per file and detect removals
        let file_key = file_path.to_string_lossy().to_string();
        let new_script_names: Vec<String> = file
            .scripts
            .iter()
            .filter(|s| s.trigger != "command")
            .map(|s| s.name.clone())
            .collect();

        let needs_rebuild = {
            let fs = self.file_scripts.read().await;
            if let Some(old_names) = fs.get(&file_key) {
                old_names.iter().any(|n| !new_script_names.contains(n))
            } else {
                false
            }
        };

        if needs_rebuild {
            // A script was removed/renamed — clone base registry and re-add all scripts
            let base = self.base_registry.read().await;
            if let Some(ref base_reg) = *base {
                let mut new_registry = base_reg.clone();
                let sources = self.all_sources.read().await;
                for (path_str, source) in sources.iter() {
                    let p = PathBuf::from(path_str);
                    let mut lx = Lexer::new(source, &p);
                    if let Ok(toks) = lx.tokenize() {
                        let mut pr = Parser::new(toks, &p);
                        if let Ok(f) = pr.parse() {
                            let ts_line = find_testscript_line(source);
                            for s in &f.scripts {
                                if s.trigger == "command" {
                                    continue;
                                }
                                let pt = s.params.iter().map(|p| p.param_type).collect();
                                new_registry.register_script(
                                    s.name.clone(),
                                    s.trigger.clone(),
                                    pt,
                                    s.return_types.clone(),
                                );
                                if let Some(ts) = ts_line
                                    && s.line >= ts
                                {
                                    new_registry.mark_test_script(&s.trigger, &s.name);
                                }
                            }
                        }
                    }
                }
                drop(sources);
                drop(base);
                *self.registry.write().await = Some(new_registry);
            }
        }

        // Update file_scripts index
        {
            let mut fs = self.file_scripts.write().await;
            fs.insert(file_key, new_script_names);
        }

        // Re-register + type check
        let ts_line = find_testscript_line(text);
        let mut reg_guard = self.registry.write().await;
        if let Some(ref mut registry) = *reg_guard {
            for script in &file.scripts {
                if script.trigger == "command" {
                    continue;
                }
                let param_types = script.params.iter().map(|p| p.param_type).collect();
                registry.register_script(
                    script.name.clone(),
                    script.trigger.clone(),
                    param_types,
                    script.return_types.clone(),
                );
                if let Some(ts) = ts_line
                    && script.line >= ts
                {
                    registry.mark_test_script(&script.trigger, &script.name);
                }
            }

            for script in &file.scripts {
                if script.trigger == "command" {
                    continue;
                }
                if !runec::trigger_table::is_valid_trigger(&script.trigger) {
                    lsp_diagnostics.push(make_diagnostic_with_source(
                        script.line,
                        0,
                        &format!("unknown trigger: '{}'", script.trigger),
                        DiagnosticSeverity::ERROR,
                        text,
                    ));
                }
                if let Some(msg) = runec::compiler::Compiler::validate_trigger_subject(
                    &script.trigger,
                    &script.name,
                    registry,
                ) {
                    lsp_diagnostics.push(make_diagnostic_with_source(
                        script.line,
                        0,
                        &msg,
                        DiagnosticSeverity::WARNING,
                        text,
                    ));
                }
            }

            let mut checker = TypeChecker::new(registry);
            let ts_line = find_testscript_line(&text);
            checker.check_file_with_test_boundary(&file, &file_path, ts_line);
            for diag in checker.diagnostics.diagnostics() {
                let severity = match diag.severity {
                    Severity::Error => DiagnosticSeverity::ERROR,
                    Severity::Warning => DiagnosticSeverity::WARNING,
                    Severity::Info => DiagnosticSeverity::INFORMATION,
                };
                lsp_diagnostics.push(make_diagnostic_with_source(
                    diag.line,
                    diag.column,
                    &diag.message,
                    severity,
                    text,
                ));
            }
        }
        drop(reg_guard);

        // Store and publish fast diagnostics immediately
        self.fast_diagnostics
            .insert(uri.clone(), lsp_diagnostics.clone());
        self.client
            .publish_diagnostics(uri.clone(), lsp_diagnostics, None)
            .await;

        // === SLOW PASS: compile + lint + pointer check (debounced) ===
        let uri_clone = uri.clone();
        let text_owned = text.to_string();
        let registry_clone = self.registry.clone();
        let all_compiled_clone = self.all_compiled.clone();
        let all_sources_clone = self.all_sources.clone();
        let client_clone = self.client.clone();
        let version_counter = self.change_version.clone();
        let fast_diags_store = self.fast_diagnostics.clone();

        tokio::spawn(async move {
            // Debounce: wait 500ms, then check if we're still the latest version
            tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;
            if version_counter.load(Ordering::SeqCst) != version {
                return;
            }

            let file_path = uri_to_path(&uri_clone);
            let reg_guard = registry_clone.read().await;
            let Some(ref registry) = *reg_guard else {
                return;
            };

            // Start with the fast-pass diagnostics (type check results)
            let mut all_diagnostics = fast_diags_store
                .get(&uri_clone)
                .map(|v| v.clone())
                .unwrap_or_default();

            // Re-lex/parse for compilation
            let tokens = {
                let mut lexer = Lexer::new(&text_owned, &file_path);
                match lexer.tokenize() {
                    Ok(t) => t,
                    Err(_) => return,
                }
            };
            let mut parser = Parser::new(tokens, &file_path);
            let Ok(file) = parser.parse() else { return };

            // Compile + lint
            let mut codegen = Compiler::new(registry.clone());
            let mut compiled_scripts = Vec::new();
            let path_key = file_path.to_string_lossy().into_owned();
            for script in &file.scripts {
                if script.trigger == "command" {
                    continue;
                }
                let mut compiled = codegen.compile_script(script);
                compiled.source_path = path_key.clone();
                compiled_scripts.push(compiled);
            }
            if !compiled_scripts.is_empty() {
                {
                    let mut source_cache_rc = std::collections::HashMap::new();
                    source_cache_rc.insert(path_key.clone(), std::rc::Rc::new(text_owned.clone()));
                    let lint_diags = lints::run_lints(&compiled_scripts, Some(&source_cache_rc));
                    for diag in lint_diags.diagnostics() {
                        all_diagnostics.push(make_diagnostic_with_source(
                            diag.line,
                            diag.column,
                            &diag.message,
                            DiagnosticSeverity::WARNING,
                            &text_owned,
                        ));
                    }
                }

                // Pointer checker
                let all_compiled = all_compiled_clone.read().await;
                if !all_compiled.is_empty() {
                    let sources = all_sources_clone.read().await;
                    let rc_cache: std::collections::HashMap<String, std::rc::Rc<String>> = sources
                        .iter()
                        .map(|(k, v)| (k.clone(), std::rc::Rc::new(v.clone())))
                        .collect();
                    drop(sources);
                    let mut ptr_checker = PointerChecker::new(&all_compiled, registry);
                    ptr_checker.set_source_cache(&rc_cache);
                    let pointer_diags = ptr_checker.run();
                    let file_path_str = file_path.to_string_lossy();
                    for diag in pointer_diags.diagnostics() {
                        if diag.file.to_string_lossy() == file_path_str {
                            all_diagnostics.push(make_diagnostic_with_source(
                                diag.line,
                                diag.column,
                                &diag.message,
                                DiagnosticSeverity::WARNING,
                                &text_owned,
                            ));
                        }
                    }
                }
            }

            // Only publish if still the latest version
            if version_counter.load(Ordering::SeqCst) == version {
                client_clone
                    .publish_diagnostics(uri_clone, all_diagnostics, None)
                    .await;
            }
        });
    }
}

async fn init_registry(
    workspace_root: &Path,
    registry_lock: &Arc<RwLock<Option<SymbolRegistry>>>,
    scripts_dir_lock: &Arc<RwLock<Option<PathBuf>>>,
    all_compiled_lock: &Arc<RwLock<Vec<CompiledScript>>>,
    all_sources_lock: &Arc<RwLock<std::collections::HashMap<String, String>>>,
    definitions_lock: &Arc<RwLock<DefIndex>>,
    param_names_lock: &Arc<RwLock<ParamNameIndex>>,
    base_registry_lock: &Arc<RwLock<Option<SymbolRegistry>>>,
    client: Client,
    documents: Arc<DashMap<Url, String>>,
) {
    let scripts_dir = workspace_root.join("content").join("scripts");
    let pack_dir = workspace_root.join("content").join("pack");

    if !scripts_dir.exists() || !pack_dir.exists() {
        info!("content/scripts or content/pack not found, skipping registry init");
        return;
    }

    let mut registry = SymbolRegistry::new();

    symloader::load_packs(&mut registry, &pack_dir);
    symloader::load_constant_files(&mut registry, &scripts_dir);
    symloader::load_game_var_types(&mut registry, &scripts_dir);

    let engine_rs2 = scripts_dir.join("engine.rs2");
    if engine_rs2.exists() {
        symloader::load_engine_command_params(&mut registry, &engine_rs2);
    }
    symloader::patch_command_return_types(&mut registry);

    // Save base registry (before scripts) for clean rebuilds
    *base_registry_lock.write().await = Some(registry.clone());

    let mut rs2_files: Vec<PathBuf> = Vec::new();
    collect_rs2_files(&scripts_dir, &mut rs2_files);
    rs2_files.sort();

    let mut defs: DefIndex = std::collections::HashMap::new();
    let mut pnames: ParamNameIndex = std::collections::HashMap::new();

    // Collect command param names from engine.rs2
    if engine_rs2.exists() {
        if let Ok(src) = std::fs::read_to_string(&engine_rs2) {
            let mut lx = Lexer::new(&src, &engine_rs2);
            if let Ok(toks) = lx.tokenize() {
                let mut pr = Parser::new(toks, &engine_rs2);
                if let Ok(f) = pr.parse() {
                    for s in &f.scripts {
                        if s.trigger == "command" {
                            let names: Vec<String> =
                                s.params.iter().map(|p| p.name.clone()).collect();
                            pnames.insert(s.name.clone(), names);
                        }
                    }
                }
            }
        }
    }

    for path in &rs2_files {
        let Ok(source) = std::fs::read_to_string(path) else {
            continue;
        };
        let mut lexer = Lexer::new(&source, path);
        let Ok(tokens) = lexer.tokenize() else {
            continue;
        };
        let mut parser = Parser::new(tokens, path);
        let Ok(file) = parser.parse() else { continue };
        // Scripts at/below a `#testscript` marker are test-only; mark them so
        // jumps/calls into them from production code are flagged.
        let ts_line = find_testscript_line(&source);
        for script in &file.scripts {
            if script.trigger == "command" {
                continue;
            }
            let param_types = script.params.iter().map(|p| p.param_type).collect();
            let names: Vec<String> = script.params.iter().map(|p| p.name.clone()).collect();
            pnames.insert(script.name.clone(), names);
            registry.register_script(
                script.name.clone(),
                script.trigger.clone(),
                param_types,
                script.return_types.clone(),
            );
            if let Some(ts) = ts_line
                && script.line >= ts
            {
                registry.mark_test_script(&script.trigger, &script.name);
            }
            let key = format!("script:{}", script.name);
            defs.insert(
                key,
                DefinitionLocation {
                    path: path.clone(),
                    line: script.line.saturating_sub(1) as u32,
                },
            );
        }
    }

    // Index command definitions from engine.rs2
    if engine_rs2.exists() {
        if let Ok(source) = std::fs::read_to_string(&engine_rs2) {
            for (line_num, line) in source.lines().enumerate() {
                let trimmed = line.trim();
                if trimmed.starts_with("[command,") {
                    if let Some(end) = trimmed.find(']') {
                        let name = &trimmed["[command,".len()..end];
                        defs.insert(
                            format!("command:{}", name),
                            DefinitionLocation {
                                path: engine_rs2.clone(),
                                line: line_num as u32,
                            },
                        );
                    }
                }
            }
        }
    }

    // Index entity definitions from .pack files
    let pack_files = &[
        "obj.pack",
        "npc.pack",
        "loc.pack",
        "inv.pack",
        "seq.pack",
        "spotanim.pack",
        "enum.pack",
        "struct.pack",
        "category.pack",
        "synth.pack",
        "hunt.pack",
        "varp.pack",
        "varn.pack",
        "vars.pack",
        "varbit.pack",
        "idk.pack",
        "interface.pack",
        "overlayinterface.pack",
        "mesanim.pack",
        "fontmetrics.pack",
        "model.pack",
        "dbrow.pack",
        "dbtable.pack",
        "stat.pack",
        "midi.pack",
        "jingle.pack",
        "param.pack",
    ];
    for pack_name in pack_files {
        let pack_path = pack_dir.join(pack_name);
        if let Ok(content) = std::fs::read_to_string(&pack_path) {
            for (line_num, line) in content.lines().enumerate() {
                let trimmed = line.trim();
                if let Some(eq_idx) = trimmed.find('=') {
                    let name = trimmed[eq_idx + 1..].trim();
                    defs.entry(format!("entity:{}", name))
                        .or_insert(DefinitionLocation {
                            path: pack_path.clone(),
                            line: line_num as u32,
                        });
                }
            }
        }
    }

    // Index constants from .constant files
    index_constant_files(&scripts_dir, &mut defs);
    // Compile all scripts for cross-file pointer checking
    info!("Compiling all scripts for pointer checker...");
    let mut codegen = Compiler::new(registry.clone());
    let mut all_compiled = Vec::new();
    let mut all_sources_map = std::collections::HashMap::new();

    for path in &rs2_files {
        let Ok(source) = std::fs::read_to_string(path) else {
            continue;
        };
        let mut lexer = Lexer::new(&source, path);
        let Ok(tokens) = lexer.tokenize() else {
            continue;
        };
        let mut parser = Parser::new(tokens, path);
        let Ok(file) = parser.parse() else { continue };
        let path_key = path.to_string_lossy().into_owned();
        all_sources_map.insert(path_key.clone(), source);
        for script in &file.scripts {
            if script.trigger == "command" {
                continue;
            }
            let mut compiled = codegen.compile_script(script);
            compiled.source_path = path_key.clone();
            all_compiled.push(compiled);
        }
    }
    info!("Compiled {} scripts total", all_compiled.len());

    *all_compiled_lock.write().await = all_compiled;
    *all_sources_lock.write().await = all_sources_map;
    *definitions_lock.write().await = defs;
    *param_names_lock.write().await = pnames;

    info!("Registry initialized");

    *scripts_dir_lock.write().await = Some(scripts_dir);
    *registry_lock.write().await = Some(registry);

    client.semantic_tokens_refresh().await.ok();
    client.inlay_hint_refresh().await.ok();

    // Re-diagnose all open documents now that registry is available
    for entry in documents.iter() {
        let uri = entry.key().clone();
        let text = entry.value().clone();
        let file_path = uri_to_path(&uri);
        let mut lsp_diagnostics = Vec::new();

        let tokens = {
            let mut lexer = Lexer::new(&text, &file_path);
            match lexer.tokenize() {
                Ok(t) => t,
                Err(_) => continue,
            }
        };
        let mut parser = Parser::new(tokens, &file_path);
        let Ok(file) = parser.parse() else { continue };

        if let Some(ref registry) = *registry_lock.read().await {
            for script in &file.scripts {
                if script.trigger == "command" {
                    continue;
                }
                if !runec::trigger_table::is_valid_trigger(&script.trigger) {
                    lsp_diagnostics.push(make_diagnostic_with_source(
                        script.line,
                        0,
                        &format!("unknown trigger: '{}'", script.trigger),
                        DiagnosticSeverity::ERROR,
                        &text,
                    ));
                }
                if let Some(msg) = runec::compiler::Compiler::validate_trigger_subject(
                    &script.trigger,
                    &script.name,
                    registry,
                ) {
                    lsp_diagnostics.push(make_diagnostic_with_source(
                        script.line,
                        0,
                        &msg,
                        DiagnosticSeverity::WARNING,
                        &text,
                    ));
                }
            }

            let mut checker = TypeChecker::new(registry);
            let ts_line = find_testscript_line(&text);
            checker.check_file_with_test_boundary(&file, &file_path, ts_line);
            for diag in checker.diagnostics.diagnostics() {
                let severity = match diag.severity {
                    Severity::Error => DiagnosticSeverity::ERROR,
                    Severity::Warning => DiagnosticSeverity::WARNING,
                    Severity::Info => DiagnosticSeverity::INFORMATION,
                };
                lsp_diagnostics.push(make_diagnostic_with_source(
                    diag.line,
                    diag.column,
                    &diag.message,
                    severity,
                    &text,
                ));
            }

            // Lints (per-file)
            let mut codegen = Compiler::new(registry.clone());
            let mut compiled_scripts = Vec::new();
            let path_key = file_path.to_string_lossy().into_owned();
            for script in &file.scripts {
                if script.trigger == "command" {
                    continue;
                }
                let mut compiled = codegen.compile_script(script);
                compiled.source_path = path_key.clone();
                compiled_scripts.push(compiled);
            }
            if !compiled_scripts.is_empty() {
                let mut source_cache = std::collections::HashMap::new();
                source_cache.insert(path_key.clone(), std::rc::Rc::new(text.clone()));
                let lint_diags = lints::run_lints(&compiled_scripts, Some(&source_cache));
                for diag in lint_diags.diagnostics() {
                    lsp_diagnostics.push(make_diagnostic_with_source(
                        diag.line,
                        diag.column,
                        &diag.message,
                        DiagnosticSeverity::WARNING,
                        &text,
                    ));
                }
            }

            // Pointer checker (cross-file)
            let all_compiled = all_compiled_lock.read().await;
            if !all_compiled.is_empty() {
                let sources = all_sources_lock.read().await;
                let rc_cache: std::collections::HashMap<String, std::rc::Rc<String>> = sources
                    .iter()
                    .map(|(k, v)| (k.clone(), std::rc::Rc::new(v.clone())))
                    .collect();
                let mut checker = PointerChecker::new(&all_compiled, registry);
                checker.set_source_cache(&rc_cache);
                let pointer_diags = checker.run();
                let file_path_str = file_path.to_string_lossy();
                for diag in pointer_diags.diagnostics() {
                    if diag.file.to_string_lossy() == file_path_str {
                        lsp_diagnostics.push(make_diagnostic_with_source(
                            diag.line,
                            diag.column,
                            &diag.message,
                            DiagnosticSeverity::WARNING,
                            &text,
                        ));
                    }
                }
            }
        }

        client.publish_diagnostics(uri, lsp_diagnostics, None).await;
    }
}

fn make_diagnostic_with_source(
    line: usize,
    col: usize,
    message: &str,
    severity: DiagnosticSeverity,
    source_text: &str,
) -> Diagnostic {
    let line_idx = line.saturating_sub(1) as u32;

    let (start_char, end_char) = if col > 0 {
        (col.saturating_sub(1) as u32, col as u32)
    } else {
        // No column info — highlight the trimmed content of the line
        let source_line = source_text.lines().nth(line_idx as usize).unwrap_or("");
        let leading = source_line.len() - source_line.trim_start().len();
        (leading as u32, source_line.trim_end().len() as u32)
    };

    Diagnostic {
        range: Range {
            start: Position {
                line: line_idx,
                character: start_char,
            },
            end: Position {
                line: line_idx,
                character: end_char,
            },
        },
        severity: Some(severity),
        source: Some("rs2".into()),
        message: message.to_string(),
        ..Default::default()
    }
}

fn format_command_hover(sym: &runec::symbol::Symbol, pnames: &ParamNameIndex) -> String {
    match &sym.kind {
        runec::symbol::SymbolKind::Command {
            param_types,
            return_types,
            ..
        } => {
            let params = if let Some(names) = pnames.get(&sym.name) {
                param_types
                    .iter()
                    .zip(names.iter())
                    .map(|(t, n)| format!("{} ${}", t, n.trim_start_matches('$')))
                    .collect::<Vec<_>>()
                    .join(", ")
            } else {
                param_types
                    .iter()
                    .map(|t| t.to_string())
                    .collect::<Vec<_>>()
                    .join(", ")
            };
            let ret = if return_types.is_empty() {
                String::new()
            } else {
                format!(
                    "({})",
                    return_types
                        .iter()
                        .map(|t| t.to_string())
                        .collect::<Vec<_>>()
                        .join(", ")
                )
            };
            format!("```rs2\n[command,{}]({}){}\n```", sym.name, params, ret)
        }
        _ => format!("`{}`", sym.name),
    }
}

fn format_script_hover(sym: &runec::symbol::Symbol, pnames: &ParamNameIndex) -> String {
    match &sym.kind {
        runec::symbol::SymbolKind::Script {
            trigger,
            param_types,
            return_types,
            ..
        } => {
            let params = if let Some(names) = pnames.get(&sym.name) {
                param_types
                    .iter()
                    .zip(names.iter())
                    .map(|(t, n)| format!("{} ${}", t, n.trim_start_matches('$')))
                    .collect::<Vec<_>>()
                    .join(", ")
            } else {
                param_types
                    .iter()
                    .map(|t| t.to_string())
                    .collect::<Vec<_>>()
                    .join(", ")
            };
            let ret = if return_types.is_empty() {
                String::new()
            } else {
                format!(
                    "({})",
                    return_types
                        .iter()
                        .map(|t| t.to_string())
                        .collect::<Vec<_>>()
                        .join(", ")
                )
            };
            format!("```rs2\n[{},{}]({}){}\n```", trigger, sym.name, params, ret)
        }
        _ => format!("`{}`", sym.name),
    }
}

fn format_game_var_hover(sym: &runec::symbol::Symbol) -> String {
    match &sym.kind {
        runec::symbol::SymbolKind::GameVar {
            var_type, category, ..
        } => {
            format!("```rs2\n{} %{} // {}\n```", var_type, sym.name, category)
        }
        _ => format!("`%{}`", sym.name),
    }
}

fn format_constant_hover(sym: &runec::symbol::Symbol) -> String {
    match &sym.kind {
        runec::symbol::SymbolKind::Constant {
            const_type,
            int_value,
            string_value,
            ..
        } => {
            let val = if let Some(s) = string_value {
                format!("\"{}\"", s)
            } else if let Some(i) = int_value {
                i.to_string()
            } else {
                "?".to_string()
            };
            format!("```rs2\n{} ^{} = {}\n```", const_type, sym.name, val)
        }
        _ => format!("`^{}`", sym.name),
    }
}

fn get_call_context(text: &str, pos: Position) -> Option<(String, usize)> {
    let line = text.lines().nth(pos.line as usize)?;
    let col = pos.character as usize;
    let bytes = line.as_bytes();
    if col == 0 || col > bytes.len() {
        return None;
    }

    let mut depth = 0i32;
    let mut commas = 0usize;
    let mut i = col.min(bytes.len()) - 1;

    loop {
        match bytes[i] {
            b')' => depth += 1,
            b'(' => {
                if depth == 0 {
                    // Found the opening paren at our depth
                    let paren_pos = i;
                    // Walk back to find the command/proc name
                    if paren_pos == 0 {
                        return None;
                    }
                    let mut j = paren_pos - 1;
                    while j > 0 && (bytes[j] == b' ' || bytes[j] == b'\t') {
                        j -= 1;
                    }
                    let end = j + 1;
                    while j > 0 && (bytes[j - 1].is_ascii_alphanumeric() || bytes[j - 1] == b'_') {
                        j -= 1;
                    }
                    if end <= j {
                        return None;
                    }
                    // Check for ~ prefix (proc call)
                    let name = if j > 0 && bytes[j - 1] == b'~' {
                        &line[j..end]
                    } else {
                        &line[j..end]
                    };
                    if name.is_empty() {
                        return None;
                    }
                    return Some((name.to_string(), commas));
                }
                depth -= 1;
            }
            b',' if depth == 0 => commas += 1,
            b'"' => {
                // Skip backwards over string
                if i > 0 {
                    i -= 1;
                    while i > 0 && bytes[i] != b'"' {
                        i -= 1;
                    }
                }
            }
            _ => {}
        }
        if i == 0 {
            break;
        }
        i -= 1;
    }
    None
}

const SCRIPT_TRIGGER_TYPES: &[&str] = &[
    "queue", "timer", "softtimer", "walktrigger", "proc", "label",
    "clientscript", "debugproc",
];

fn pack_type_matches(pack_type: &str, expected_type: &str) -> bool {
    if pack_type == expected_type {
        return true;
    }
    match (pack_type, expected_type) {
        ("obj", "namedobj") | ("namedobj", "obj") => true,
        _ => false,
    }
}

fn config_extension(pack_type: &str) -> &str {
    match pack_type {
        "interface" => "if",
        "namedobj" => "obj",
        _ => pack_type,
    }
}

fn read_entity_config(name: &str, pack_type: &str, content_dir: &Path) -> Option<String> {
    let ext = config_extension(pack_type);
    fn search_dir(dir: &Path, ext: &str, name: &str) -> Option<String> {
        let Ok(entries) = std::fs::read_dir(dir) else {
            return None;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                if let Some(result) = search_dir(&path, ext, name) {
                    return Some(result);
                }
            } else if path.extension().and_then(|e| e.to_str()) == Some(ext) {
                if let Ok(content) = std::fs::read_to_string(&path) {
                    let header = format!("[{}]", name);
                    if let Some(start) = content.find(&header) {
                        let block = &content[start..];
                        let end = block[header.len()..]
                            .find("\n[")
                            .map(|i| i + header.len())
                            .unwrap_or(block.len());
                        let section = block[..end].trim();
                        return Some(format!("```\n{}\n```", section));
                    }
                }
            }
        }
        None
    }
    search_dir(content_dir, ext, name)
}

fn find_local_var_def_text(text: &str, var_name: &str, cursor_line: u32) -> Option<String> {
    let target = format!("${}", var_name);
    let mut scope_start = 0;
    for (line_idx, line) in text.lines().enumerate() {
        let trimmed = line.trim_start();
        if trimmed.starts_with('[') && trimmed.contains(']') {
            if line_idx <= cursor_line as usize {
                scope_start = line_idx;
            } else {
                break;
            }
        }
    }
    for line in text.lines().skip(scope_start) {
        let trimmed = line.trim_start();
        let is_header = trimmed.starts_with('[');
        let is_def = trimmed.starts_with("def_");
        if !is_header && !is_def {
            continue;
        }
        if line.contains(&target) {
            let after_pos = line.find(&target).unwrap() + target.len();
            if after_pos >= line.len()
                || !line.as_bytes()[after_pos].is_ascii_alphanumeric()
                    && line.as_bytes()[after_pos] != b'_'
            {
                return Some(trimmed.trim_end_matches(';').trim().to_string());
            }
        }
    }
    None
}

fn is_inside_string(text: &str, pos: Position) -> bool {
    let Some(line) = text.lines().nth(pos.line as usize) else {
        return false;
    };
    let col = pos.character as usize;
    let mut in_string = false;
    let mut in_interp = false;
    for (i, c) in line.chars().enumerate() {
        if i >= col {
            break;
        }
        if c == '"' && !in_interp {
            in_string = !in_string;
        }
        if in_string && c == '<' {
            in_interp = true;
        }
        if in_string && c == '>' {
            in_interp = false;
        }
    }
    in_string && !in_interp
}

fn word_at_position(text: &str, pos: Position) -> String {
    let Some(line) = text.lines().nth(pos.line as usize) else {
        return String::new();
    };
    let mut col = pos.character as usize;
    let bytes = line.as_bytes();
    if col >= bytes.len() {
        return String::new();
    }

    let is_word_char = |b: u8| b.is_ascii_alphanumeric() || b == b'_';

    if matches!(bytes[col], b'~' | b'@' | b'^' | b'$' | b'%') {
        if col + 1 < bytes.len() && is_word_char(bytes[col + 1]) {
            col += 1;
        }
    }

    // Expand left
    let mut start = col;
    while start > 0 && is_word_char(bytes[start - 1]) {
        start -= 1;
    }

    // Expand right
    let mut end = col;
    while end < bytes.len() && is_word_char(bytes[end]) {
        end += 1;
    }

    if start >= end {
        return String::new();
    }
    line[start..end].to_string()
}

fn word_prefix_at_position(text: &str, pos: Position) -> Option<u8> {
    let line = text.lines().nth(pos.line as usize)?;
    let col = pos.character as usize;
    let bytes = line.as_bytes();
    if col >= bytes.len() {
        return None;
    }
    let is_word_char = |b: u8| b.is_ascii_alphanumeric() || b == b'_';
    if matches!(bytes[col], b'~' | b'@' | b'^' | b'$' | b'%') {
        if col + 1 < bytes.len() && is_word_char(bytes[col + 1]) {
            return Some(bytes[col]);
        }
        return None;
    }
    let mut start = col;
    while start > 0 && is_word_char(bytes[start - 1]) {
        start -= 1;
    }
    if start > 0 && matches!(bytes[start - 1], b'~' | b'@' | b'^' | b'$' | b'%') {
        return Some(bytes[start - 1]);
    }
    None
}

fn find_local_var_definition(text: &str, var_name: &str, cursor_line: u32) -> Option<(u32, u32)> {
    let target = format!("${}", var_name);

    let mut scope_start = 0;
    let mut scope_end = text.lines().count();
    for (line_idx, line) in text.lines().enumerate() {
        let trimmed = line.trim_start();
        if trimmed.starts_with('[') && trimmed.contains(']') {
            if line_idx <= cursor_line as usize {
                scope_start = line_idx;
            } else {
                scope_end = line_idx;
                break;
            }
        }
    }

    for (line_idx, line) in text.lines().enumerate().skip(scope_start).take(scope_end - scope_start)
    {
        let trimmed = line.trim_start();
        let is_header = trimmed.starts_with('[');
        let is_def = trimmed.starts_with("def_");
        if !is_header && !is_def {
            continue;
        }
        if let Some(pos) = line.find(&target) {
            let after = pos + target.len();
            if after >= line.len()
                || !line.as_bytes()[after].is_ascii_alphanumeric()
                    && line.as_bytes()[after] != b'_'
            {
                return Some((line_idx as u32, pos as u32));
            }
        }
    }
    None
}

fn find_testscript_line(source: &str) -> Option<usize> {
    for (i, line) in source.lines().enumerate() {
        if line.trim() == "#testscript" {
            return Some(i + 1);
        }
    }
    None
}

fn uri_to_path(uri: &Url) -> PathBuf {
    uri.to_file_path()
        .unwrap_or_else(|_| PathBuf::from(uri.path()))
}

// ─────────────────────── Code action helpers ───────────────────────

fn is_ident_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_'
}

/// Extract the text between the first pair of single quotes in a message.
fn first_quoted(msg: &str) -> Option<String> {
    let start = msg.find('\'')? + 1;
    let end = msg[start..].find('\'')? + start;
    Some(msg[start..end].to_string())
}

/// Classic Levenshtein edit distance (used to rank "did you mean" candidates).
fn levenshtein(a: &str, b: &str) -> usize {
    let a: Vec<char> = a.chars().collect();
    let b: Vec<char> = b.chars().collect();
    let mut prev: Vec<usize> = (0..=b.len()).collect();
    let mut cur = vec![0usize; b.len() + 1];
    for (i, &ca) in a.iter().enumerate() {
        cur[0] = i + 1;
        for (j, &cb) in b.iter().enumerate() {
            let cost = if ca == cb { 0 } else { 1 };
            cur[j + 1] = (prev[j + 1] + 1).min(cur[j] + 1).min(prev[j] + cost);
        }
        std::mem::swap(&mut prev, &mut cur);
    }
    prev[b.len()]
}

/// Find names registered under `prefix` (e.g. "command:") in the definition
/// index that are close to `name`, ranked by edit distance.
fn fuzzy_matches(defs: &DefIndex, prefix: &str, name: &str, limit: usize) -> Vec<String> {
    let name_l = name.to_lowercase();
    let threshold = (name.len() / 3).max(2);
    let mut scored: Vec<(usize, String)> = defs
        .keys()
        .filter_map(|k| k.strip_prefix(prefix))
        .filter(|cand| *cand != name)
        .map(|cand| (levenshtein(&name_l, &cand.to_lowercase()), cand.to_string()))
        .filter(|(d, _)| *d <= threshold)
        .collect();
    scored.sort_by(|a, b| a.0.cmp(&b.0).then_with(|| a.1.cmp(&b.1)));
    scored.dedup_by(|a, b| a.1 == b.1);
    scored.into_iter().take(limit).map(|(_, s)| s).collect()
}

/// Locate the precise span of `bare` on the diagnostic's start line.
/// Locate the precise span of `bare` on the diagnostic's start line, honoring
/// sigil context: with `sigil = Some(s)` the match must be preceded by `s`
/// (e.g. `~myproc`); with `None` the match must be a bare token NOT preceded
/// by any sigil (`$ ~ @ ^ %`) — so `coordx` in `def_int $coordx = coordx`
/// resolves to the command on the right, not the `$coordx` variable name.
fn find_name_range(
    lines: &[&str],
    diag: &Diagnostic,
    bare: &str,
    sigil: Option<char>,
) -> Option<Range> {
    let line_idx = diag.range.start.line as usize;
    let line = lines.get(line_idx)?;
    let bytes = line.as_bytes();
    let mut start = 0;
    while let Some(idx) = line[start..].find(bare) {
        let col = start + idx;
        let after = col + bare.len();
        let after_ok = after >= line.len() || !is_ident_byte(bytes[after]);
        let prev = col.checked_sub(1).map(|i| bytes[i]);
        let before_ok = match sigil {
            Some(s) => prev == Some(s as u8),
            None => match prev {
                None => true,
                Some(b) => {
                    !is_ident_byte(b) && !matches!(b, b'$' | b'~' | b'@' | b'^' | b'%')
                }
            },
        };
        if before_ok && after_ok {
            return Some(Range {
                start: Position { line: line_idx as u32, character: col as u32 },
                end: Position { line: line_idx as u32, character: after as u32 },
            });
        }
        start = after;
    }
    None
}

/// Position at the very end of the document (for end-of-file insertions).
fn end_position(text: &str) -> Position {
    let line = text.matches('\n').count() as u32;
    let last_line_len = text.rsplit('\n').next().unwrap_or("").chars().count() as u32;
    Position { line, character: last_line_len }
}

fn single_file_edit(uri: &Url, edits: Vec<TextEdit>) -> WorkspaceEdit {
    let mut changes = std::collections::HashMap::new();
    changes.insert(uri.clone(), edits);
    WorkspaceEdit {
        changes: Some(changes),
        document_changes: None,
        change_annotations: None,
    }
}

fn quickfix(
    title: String,
    kind: CodeActionKind,
    edit: WorkspaceEdit,
    diag: Option<&Diagnostic>,
) -> CodeActionOrCommand {
    CodeActionOrCommand::CodeAction(CodeAction {
        title,
        kind: Some(kind),
        diagnostics: diag.map(|d| vec![d.clone()]),
        edit: Some(edit),
        command: None,
        is_preferred: None,
        disabled: None,
        data: None,
    })
}

/// "Did you mean …?" replacement suggestions for an unresolved symbol.
fn push_did_you_mean(
    actions: &mut Vec<CodeActionOrCommand>,
    uri: &Url,
    lines: &[&str],
    diag: &Diagnostic,
    defs: &DefIndex,
    prefix: &str,
    bare: &str,
    sigil: Option<char>,
) {
    let Some(range) = find_name_range(lines, diag, bare, sigil) else {
        return;
    };
    for cand in fuzzy_matches(defs, prefix, bare, 5) {
        let title = match sigil {
            Some(s) => format!("Change to '{s}{cand}'"),
            None => format!("Change to '{cand}'"),
        };
        let edit = TextEdit {
            range,
            new_text: cand,
        };
        actions.push(quickfix(
            title,
            CodeActionKind::QUICKFIX,
            single_file_edit(uri, vec![edit]),
            Some(diag),
        ));
    }
}

/// Generate a stub `[proc,name]` / `[label,name]`. Inserted before a
/// `#testscript` marker if present (so it stays in production code),
/// otherwise at the end of the file.
fn push_create_script(
    actions: &mut Vec<CodeActionOrCommand>,
    uri: &Url,
    text: &str,
    name: &str,
    trigger: &str,
) {
    let stub_body = if trigger == "proc" {
        format!("[proc,{name}]\nreturn;\n")
    } else {
        format!("[label,{name}]\n")
    };
    // `find_testscript_line` returns the 1-based line after the marker;
    // subtract 1 to land on the marker line itself.
    let (pos, new_text) = match find_testscript_line(text).map(|l| l - 1) {
        Some(marker_line) => (
            Position { line: marker_line as u32, character: 0 },
            format!("{stub_body}\n"),
        ),
        None => (end_position(text), format!("\n{stub_body}")),
    };
    let edit = TextEdit {
        range: Range { start: pos, end: pos },
        new_text,
    };
    actions.push(quickfix(
        format!("Create {trigger} '{name}'"),
        CodeActionKind::QUICKFIX,
        single_file_edit(uri, vec![edit]),
        None,
    ));
}

/// Offer to wrap a bare unresolved identifier in quotes (string literal).
fn push_quote_as_string(
    actions: &mut Vec<CodeActionOrCommand>,
    uri: &Url,
    lines: &[&str],
    diag: &Diagnostic,
    bare: &str,
) {
    let Some(range) = find_name_range(lines, diag, bare, None) else {
        return;
    };
    let edit = TextEdit {
        range,
        new_text: format!("\"{bare}\""),
    };
    actions.push(quickfix(
        format!("Convert '{bare}' to string literal \"{bare}\""),
        CodeActionKind::QUICKFIX,
        single_file_edit(uri, vec![edit]),
        Some(diag),
    ));
}

/// For a type mismatch on a `def_<given>` declaration, offer to change the
/// declared type to the expected one.
fn push_type_mismatch_fixes(
    actions: &mut Vec<CodeActionOrCommand>,
    uri: &Url,
    lines: &[&str],
    diag: &Diagnostic,
    msg: &str,
) {
    let parts: Vec<&str> = msg.split('\'').collect();
    let (Some(given), Some(expected)) = (parts.get(1), parts.get(3)) else {
        return;
    };
    if given.contains("arg(s)") || expected.contains("arg(s)") {
        return;
    }
    let line_idx = diag.range.start.line as usize;
    let Some(line) = lines.get(line_idx) else {
        return;
    };
    let needle = format!("def_{given}");
    if let Some(col) = line.find(&needle) {
        let range = Range {
            start: Position { line: line_idx as u32, character: col as u32 },
            end: Position {
                line: line_idx as u32,
                character: (col + needle.len()) as u32,
            },
        };
        let edit = TextEdit {
            range,
            new_text: format!("def_{expected}"),
        };
        actions.push(quickfix(
            format!("Change declared type to 'def_{expected}'"),
            CodeActionKind::QUICKFIX,
            single_file_edit(uri, vec![edit]),
            Some(diag),
        ));
    }
}

/// Find the header line (`[trigger,name]`) of the script enclosing `start_line`.
fn find_enclosing_header(lines: &[&str], start_line: usize) -> Option<usize> {
    let upper = start_line.min(lines.len().saturating_sub(1));
    for i in (0..=upper).rev() {
        let t = lines[i].trim_start();
        if t.starts_with('[') && t.contains(',') && t.contains(']') {
            return Some(i);
        }
    }
    None
}

/// Collect `$vars` used in `body` that are NOT declared (`def_…`) within it,
/// preserving first-occurrence order. These become extracted-proc parameters.
fn free_vars(body: &str) -> Vec<String> {
    let bytes = body.as_bytes();
    let mut declared = std::collections::HashSet::new();
    let mut used = Vec::new();
    let mut seen = std::collections::HashSet::new();
    let mut i = 0;
    while i < body.len() {
        if bytes[i] == b'$' {
            let start = i + 1;
            let mut j = start;
            while j < body.len() && is_ident_byte(bytes[j]) {
                j += 1;
            }
            if j > start {
                let name = body[start..j].to_string();
                // Determine the token immediately preceding `$`.
                let mut p = i;
                while p > 0 && (bytes[p - 1] as char).is_whitespace() {
                    p -= 1;
                }
                let mut k = p;
                while k > 0 && is_ident_byte(bytes[k - 1]) {
                    k -= 1;
                }
                let prev = &body[k..p];
                if prev.starts_with("def_") {
                    declared.insert(name);
                } else if seen.insert(name.clone()) {
                    used.push(name);
                }
            }
            i = j;
        } else {
            i += 1;
        }
    }
    used.into_iter().filter(|n| !declared.contains(n)).collect()
}

/// Look up the declared type of `$var` in `region` (e.g. `def_obj $var` or a
/// `(obj $var)` parameter). Returns the bare type name (sans `def_`).
fn find_var_type(region: &str, var: &str) -> Option<String> {
    let needle = format!("${var}");
    let bytes = region.as_bytes();
    let mut search = 0;
    while let Some(rel) = region[search..].find(&needle) {
        let pos = search + rel;
        let after = pos + needle.len();
        let boundary = after >= region.len() || !is_ident_byte(bytes[after]);
        // A declaration has a type token followed by whitespace before `$`.
        if boundary && pos > 0 && (bytes[pos - 1] as char).is_whitespace() {
            let mut j = pos - 1;
            while j > 0 && (bytes[j] as char).is_whitespace() {
                j -= 1;
            }
            if !(bytes[j] as char).is_whitespace() {
                let end = j + 1;
                let mut k = end;
                while k > 0 && is_ident_byte(bytes[k - 1]) {
                    k -= 1;
                }
                let tok = &region[k..end];
                let ty = tok.strip_prefix("def_").unwrap_or(tok);
                if !ty.is_empty()
                    && ty.chars().next().is_some_and(|c| c.is_ascii_alphabetic())
                    && ty.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
                {
                    return Some(ty.to_string());
                }
            }
        }
        search = after;
    }
    None
}

/// Split a string on top-level commas (ignoring those nested in parens/strings).
fn split_top_commas(s: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut depth = 0i32;
    let mut in_str = false;
    let mut cur = String::new();
    for c in s.chars() {
        match c {
            '"' => {
                in_str = !in_str;
                cur.push(c);
            }
            '(' if !in_str => {
                depth += 1;
                cur.push(c);
            }
            ')' if !in_str => {
                depth -= 1;
                cur.push(c);
            }
            ',' if !in_str && depth == 0 => {
                out.push(std::mem::take(&mut cur));
            }
            _ => cur.push(c),
        }
    }
    if !cur.trim().is_empty() {
        out.push(cur);
    }
    out
}

/// Find the argument list of the first `return(...)` in `body`.
fn first_return_values(body: &str) -> Option<Vec<String>> {
    let bytes = body.as_bytes();
    let mut search = 0;
    while let Some(rel) = body[search..].find("return") {
        let pos = search + rel;
        let before_ok = pos == 0 || !is_ident_byte(bytes[pos - 1]);
        let mut k = pos + 6;
        while k < body.len() && (bytes[k] as char).is_whitespace() {
            k += 1;
        }
        if before_ok && k < body.len() && bytes[k] == b'(' {
            let start = k + 1;
            let mut depth = 0i32;
            let mut m = k;
            while m < body.len() {
                match bytes[m] {
                    b'(' => depth += 1,
                    b')' => {
                        depth -= 1;
                        if depth == 0 {
                            return Some(split_top_commas(&body[start..m]));
                        }
                    }
                    _ => {}
                }
                m += 1;
            }
        }
        search = pos + 6;
    }
    None
}

/// Best-effort type inference for a single return/argument expression.
fn infer_value_type(val: &str, region: &str, registry: Option<&SymbolRegistry>) -> String {
    let v = val.trim();
    if v.is_empty() {
        return String::new();
    }
    if v == "true" || v == "false" {
        return "boolean".to_string();
    }
    if v.starts_with('"') {
        return "string".to_string();
    }
    if v.parse::<i64>().is_ok() {
        return "int".to_string();
    }
    if let Some(rest) = v.strip_prefix('$') {
        let name: String = rest
            .chars()
            .take_while(|c| c.is_ascii_alphanumeric() || *c == '_')
            .collect();
        return find_var_type(region, &name).unwrap_or_else(|| "int".to_string());
    }
    // Bare identifier — try the entity registry (obj/npc/loc/…).
    if v.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
        if let Some(reg) = registry {
            if let Some(sym) = reg.lookup_entity_id(v) {
                if let runec::symbol::SymbolKind::Constant { const_type, .. } = &sym.kind {
                    return const_type.name().to_string();
                }
            }
        }
    }
    "int".to_string()
}

/// A valid default-value literal placeholder for a parameter of the given
/// type, so the generated call compiles as-is (e.g. string → `""`, int → `0`).
fn placeholder_literal(t: runec::types::Type) -> &'static str {
    use runec::types::{BaseVarType, Type};
    match t.base_type() {
        BaseVarType::String => "\"\"",
        BaseVarType::Long => "0",
        BaseVarType::Integer => {
            if t == Type::Boolean {
                "false"
            } else {
                "0"
            }
        }
    }
}

/// Quick fix for a bare command used as a value: replace `name` with a call
/// `name(<defaults>)` whose arguments are valid placeholder literals built
/// from the command's parameter types (e.g. `mes` → `mes("")`).
fn push_call_with_args(
    actions: &mut Vec<CodeActionOrCommand>,
    uri: &Url,
    lines: &[&str],
    diag: &Diagnostic,
    name: &str,
    registry: Option<&SymbolRegistry>,
) {
    let Some(range) = find_name_range(lines, diag, name, None) else {
        return;
    };
    // Build the argument list from default literals for each parameter type.
    let arg_list = registry
        .and_then(|reg| reg.lookup_command(name).cloned())
        .and_then(|sym| match sym.kind {
            runec::symbol::SymbolKind::Command { param_types, .. } => Some(
                param_types
                    .iter()
                    .map(|t| placeholder_literal(*t))
                    .collect::<Vec<_>>()
                    .join(", "),
            ),
            _ => None,
        })
        .unwrap_or_default();
    let new_text = format!("{name}({arg_list})");
    actions.push(quickfix(
        format!("Call '{name}' with arguments — {new_text}"),
        CodeActionKind::QUICKFIX,
        single_file_edit(uri, vec![TextEdit { range, new_text }]),
        Some(diag),
    ));
}

/// Extract the selected lines into a new proc/label, inferring parameters from
/// referenced locals and (for procs) the return type from `return(...)`. The
/// new script is inserted just before the enclosing script header so it stays
/// in the same section (never after `#testscript`).
fn build_extract_action(
    uri: &Url,
    lines: &[&str],
    range: Range,
    trigger: &str,
    registry: Option<&SymbolRegistry>,
) -> Option<CodeActionOrCommand> {
    let start_line = range.start.line as usize;
    let mut end_line = range.end.line as usize;
    // A selection ending at column 0 doesn't include that final line.
    if range.end.character == 0 && end_line > start_line {
        end_line -= 1;
    }
    if end_line >= lines.len() {
        return None;
    }
    let body = lines[start_line..=end_line].join("\n");
    if body.trim().is_empty() {
        return None;
    }

    let header_line = find_enclosing_header(lines, start_line)?;
    // Region used for type inference: the enclosing script up to the selection.
    let region = lines[header_line..=end_line].join("\n");

    // Parameters: free locals referenced in the body, typed from the region.
    let params = free_vars(&body);
    let param_decls: Vec<String> = params
        .iter()
        .map(|p| {
            let ty = find_var_type(&region, p).unwrap_or_else(|| "int".to_string());
            format!("{ty} ${p}")
        })
        .collect();
    let call_args = if params.is_empty() {
        String::new()
    } else {
        format!("({})", params.iter().map(|p| format!("${p}")).collect::<Vec<_>>().join(", "))
    };

    // Return type (procs only): inferred from the first return statement.
    let ret_types: Vec<String> = if trigger == "proc" {
        first_return_values(&body)
            .map(|vals| {
                vals.iter()
                    .map(|v| infer_value_type(v, &region, registry))
                    .filter(|t| !t.is_empty())
                    .collect()
            })
            .unwrap_or_default()
    } else {
        Vec::new()
    };

    let name = format!("extracted_{trigger}");
    let sigil = if trigger == "proc" { "~" } else { "@" };
    let call = format!("{sigil}{name}{call_args}");

    // Build the new script header signature.
    let mut signature = String::new();
    if !param_decls.is_empty() {
        signature.push_str(&format!("({})", param_decls.join(", ")));
    } else if !ret_types.is_empty() {
        signature.push_str("()");
    }
    if !ret_types.is_empty() {
        signature.push_str(&format!("({})", ret_types.join(", ")));
    }

    // Replace the selected lines with the call.
    let end_char = lines[end_line].chars().count() as u32;
    let replace_edit = TextEdit {
        range: Range {
            start: Position { line: start_line as u32, character: 0 },
            end: Position { line: end_line as u32, character: end_char },
        },
        new_text: call,
    };

    // Insert the new script just before the enclosing header.
    let new_script = format!("[{trigger},{name}]{signature}\n{body}\n\n");
    let insert_pos = Position { line: header_line as u32, character: 0 };
    let insert_edit = TextEdit {
        range: Range { start: insert_pos, end: insert_pos },
        new_text: new_script,
    };

    Some(quickfix(
        format!("Extract selection to {trigger}"),
        CodeActionKind::REFACTOR_EXTRACT,
        single_file_edit(uri, vec![insert_edit, replace_edit]),
        None,
    ))
}

fn index_constant_files(scripts_dir: &Path, defs: &mut DefIndex) {
    fn walk_constants(dir: &Path, defs: &mut DefIndex) {
        let Ok(entries) = std::fs::read_dir(dir) else {
            return;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                walk_constants(&path, defs);
            } else if path.extension().and_then(|e| e.to_str()) == Some("constant") {
                if let Ok(content) = std::fs::read_to_string(&path) {
                    for (line_num, line) in content.lines().enumerate() {
                        let trimmed = line.trim();
                        if trimmed.is_empty() || trimmed.starts_with("//") {
                            continue;
                        }
                        // Format: ^name = value or name = value
                        let name = trimmed.split('=').next().unwrap_or("").trim();
                        let name = name.strip_prefix('^').unwrap_or(name);
                        if !name.is_empty() {
                            defs.insert(
                                format!("constant:{}", name),
                                DefinitionLocation {
                                    path: path.clone(),
                                    line: line_num as u32,
                                },
                            );
                        }
                    }
                }
            }
        }
    }
    walk_constants(scripts_dir, defs);
}

fn collect_rs2_files(dir: &Path, out: &mut Vec<PathBuf>) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            collect_rs2_files(&path, out);
        } else if path.extension().and_then(|e| e.to_str()) == Some("rs2") {
            out.push(path);
        }
    }

}

#[tower_lsp::async_trait]
impl LanguageServer for Backend {
    async fn initialize(&self, params: InitializeParams) -> Result<InitializeResult> {
        info!("RuneScript 2 LSP server initializing");

        if let Some(root_uri) = params.root_uri {
            if let Ok(root_path) = root_uri.to_file_path() {
                let registry = self.registry.clone();
                let scripts_dir_lock = self.scripts_dir.clone();
                let all_compiled = self.all_compiled.clone();
                let all_sources = self.all_sources.clone();
                let definitions = self.definitions.clone();
                let param_names = self.param_names.clone();
                let base_registry = self.base_registry.clone();
                let client = self.client.clone();
                let documents = self.documents.clone();
                tokio::spawn(async move {
                    init_registry(
                        &root_path,
                        &registry,
                        &scripts_dir_lock,
                        &all_compiled,
                        &all_sources,
                        &definitions,
                        &param_names,
                        &base_registry,
                        client,
                        documents,
                    )
                    .await;
                });
            }
        }

        Ok(InitializeResult {
            capabilities: ServerCapabilities {
                text_document_sync: Some(TextDocumentSyncCapability::Options(
                    TextDocumentSyncOptions {
                        open_close: Some(true),
                        change: Some(TextDocumentSyncKind::FULL),
                        save: Some(TextDocumentSyncSaveOptions::SaveOptions(SaveOptions {
                            include_text: Some(true),
                        })),
                        ..Default::default()
                    },
                )),
                hover_provider: Some(HoverProviderCapability::Simple(true)),
                definition_provider: Some(OneOf::Left(true)),
                references_provider: Some(OneOf::Left(true)),
                code_action_provider: Some(CodeActionProviderCapability::Options(
                    CodeActionOptions {
                        code_action_kinds: Some(vec![
                            CodeActionKind::QUICKFIX,
                            CodeActionKind::REFACTOR_EXTRACT,
                        ]),
                        work_done_progress_options: Default::default(),
                        resolve_provider: Some(false),
                    },
                )),
                // inlay hints provided by the plugin, not the LSP
                semantic_tokens_provider: Some(
                    SemanticTokensServerCapabilities::SemanticTokensOptions(
                        SemanticTokensOptions {
                            legend: SemanticTokensLegend {
                                token_types: TOKEN_TYPES.to_vec(),
                                token_modifiers: vec![],
                            },
                            full: Some(SemanticTokensFullOptions::Bool(true)),
                            range: None,
                            work_done_progress_options: Default::default(),
                        },
                    ),
                ),
                ..Default::default()
            },
            server_info: Some(ServerInfo {
                name: "rs2-lsp".to_string(),
                version: Some(env!("CARGO_PKG_VERSION").to_string()),
            }),
        })
    }

    async fn initialized(&self, _: InitializedParams) {
        info!("RuneScript 2 LSP server initialized");
        self.client
            .log_message(MessageType::INFO, "RuneScript 2 LSP server initialized")
            .await;
    }

    async fn shutdown(&self) -> Result<()> {
        info!("RuneScript 2 LSP server shutting down");
        Ok(())
    }

    async fn did_open(&self, params: DidOpenTextDocumentParams) {
        let uri = params.text_document.uri;
        let text = params.text_document.text;
        self.documents.insert(uri.clone(), text.clone());
        self.diagnose(&uri, &text).await;
    }

    async fn did_change(&self, params: DidChangeTextDocumentParams) {
        let uri = params.text_document.uri;
        if let Some(change) = params.content_changes.into_iter().last() {
            self.documents.insert(uri.clone(), change.text.clone());

            // Keep all_sources in sync so registry rebuilds use latest content
            let file_path = uri_to_path(&uri);
            let path_key = file_path.to_string_lossy().to_string();
            {
                let mut sources = self.all_sources.write().await;
                sources.insert(path_key, change.text.clone());
            }

            self.diagnose(&uri, &change.text).await;

            // Re-diagnose other open documents for cross-file errors
            let other_docs: Vec<(Url, String)> = self
                .documents
                .iter()
                .filter(|e| e.key() != &uri)
                .map(|e| (e.key().clone(), e.value().clone()))
                .collect();
            for (other_uri, other_text) in other_docs {
                self.diagnose(&other_uri, &other_text).await;
            }
        }
    }

    async fn did_save(&self, params: DidSaveTextDocumentParams) {
        let uri = &params.text_document.uri;
        let Some(text) = params
            .text
            .or_else(|| self.documents.get(uri).map(|v| v.clone()))
        else {
            return;
        };

        let file_path = uri_to_path(uri);

        // Re-register scripts from this file in the registry
        let mut reg_guard = self.registry.write().await;
        if let Some(ref mut registry) = *reg_guard {
            let mut lexer = Lexer::new(&text, &file_path);
            if let Ok(tokens) = lexer.tokenize() {
                let mut parser = Parser::new(tokens, &file_path);
                if let Ok(file) = parser.parse() {
                    let ts_line = find_testscript_line(&text);
                    for script in &file.scripts {
                        if script.trigger == "command" {
                            continue;
                        }
                        let param_types = script.params.iter().map(|p| p.param_type).collect();
                        registry.register_script(
                            script.name.clone(),
                            script.trigger.clone(),
                            param_types,
                            script.return_types.clone(),
                        );
                        if let Some(ts) = ts_line
                            && script.line >= ts
                        {
                            registry.mark_test_script(&script.trigger, &script.name);
                        }
                    }
                }
            }
        }
        drop(reg_guard);

        // Re-compile this file's scripts into the all_compiled set
        let reg_guard = self.registry.read().await;
        if let Some(ref registry) = *reg_guard {
            let mut lexer = Lexer::new(&text, &file_path);
            if let Ok(tokens) = lexer.tokenize() {
                let mut parser = Parser::new(tokens, &file_path);
                if let Ok(file) = parser.parse() {
                    let mut codegen = Compiler::new(registry.clone());
                    let path_key = file_path.to_string_lossy().into_owned();
                    let mut new_compiled = Vec::new();
                    for script in &file.scripts {
                        if script.trigger == "command" {
                            continue;
                        }
                        let mut compiled = codegen.compile_script(script);
                        compiled.source_path = path_key.clone();
                        new_compiled.push(compiled);
                    }

                    // Replace this file's scripts in the global compiled list
                    let mut all_compiled = self.all_compiled.write().await;
                    all_compiled.retain(|s| s.source_path != path_key);
                    all_compiled.extend(new_compiled);

                    // Update source cache
                    let mut sources = self.all_sources.write().await;
                    sources.insert(path_key.clone(), text.clone());

                    // Update definitions for this file
                    let mut defs = self.definitions.write().await;
                    // Remove old defs from this file
                    defs.retain(|_, loc| loc.path != file_path);
                    // Re-add
                    let mut lexer2 = Lexer::new(&text, &file_path);
                    if let Ok(tokens2) = lexer2.tokenize() {
                        let mut parser2 = Parser::new(tokens2, &file_path);
                        if let Ok(file2) = parser2.parse() {
                            for script in &file2.scripts {
                                if script.trigger == "command" {
                                    continue;
                                }
                                let key = format!("script:{}", script.name);
                                defs.insert(
                                    key,
                                    DefinitionLocation {
                                        path: file_path.clone(),
                                        line: script.line.saturating_sub(1) as u32,
                                    },
                                );
                            }
                        }
                    }
                }
            }
        }
    }

    async fn did_close(&self, params: DidCloseTextDocumentParams) {
        let uri = &params.text_document.uri;
        self.documents.remove(uri);
        self.client
            .publish_diagnostics(uri.clone(), vec![], None)
            .await;
    }

    async fn hover(&self, params: HoverParams) -> Result<Option<Hover>> {
        let uri = &params.text_document_position_params.text_document.uri;
        let pos = params.text_document_position_params.position;

        let Some(text) = self.documents.get(uri).map(|v| v.clone()) else {
            return Ok(None);
        };

        let word = word_at_position(&text, pos);
        if word.is_empty() {
            return Ok(None);
        }

        // Skip hover inside string literals
        if is_inside_string(&text, pos) {
            return Ok(None);
        }

        let prefix = word_prefix_at_position(&text, pos);

        let markdown: Option<String> = match prefix {
            Some(b'$') => find_local_var_def_text(&text, &word, pos.line)
                .map(|line| format!("```rs2\n{}\n```", line)),
            Some(b'~') | Some(b'@') => {
                let reg = self.registry.read().await;
                let pn = self.param_names.read().await;
                reg.as_ref()
                    .and_then(|r| r.lookup_script(&word).map(|s| format_script_hover(s, &pn)))
            }
            Some(b'%') => {
                let reg = self.registry.read().await;
                reg.as_ref()
                    .and_then(|r| r.lookup_game_var(&word).map(format_game_var_hover))
            }
            Some(b'^') => {
                let reg = self.registry.read().await;
                reg.as_ref()
                    .and_then(|r| r.lookup_constant(&word).map(format_constant_hover))
            }
            _ => {
                // Phase 1: check command and script (registry lock only)
                let (cmd_result, script_result) = {
                    let reg = self.registry.read().await;
                    let pn = self.param_names.read().await;
                    match reg.as_ref() {
                        Some(r) => (
                            r.lookup_command(&word).map(|s| format_command_hover(s, &pn)),
                            r.lookup_script(&word).map(|s| format_script_hover(s, &pn)),
                        ),
                        None => (None, None),
                    }
                }; // locks dropped

                if cmd_result.is_some() {
                    cmd_result
                } else {
                    // Check argument context to determine expected type
                    let expected_type = if let Some((call_name, arg_idx)) = get_call_context(&text, pos) {
                        let reg = self.registry.read().await;
                        let pn = self.param_names.read().await;
                        reg.as_ref().and_then(|r| {
                            // Get param types from command or script
                            let param_types = if let Some(sym) = r.lookup_command(&call_name) {
                                match &sym.kind {
                                    runec::symbol::SymbolKind::Command { param_types, .. } => Some(param_types.clone()),
                                    _ => None,
                                }
                            } else if let Some(sym) = r.lookup_script(&call_name) {
                                match &sym.kind {
                                    runec::symbol::SymbolKind::Script { param_types, .. } => Some(param_types.clone()),
                                    _ => None,
                                }
                            } else {
                                None
                            };
                            param_types.and_then(|types| types.get(arg_idx).map(|t| t.name().to_string()))
                        })
                    } else {
                        None
                    };

                    // If expected type is a script trigger type, show script hover
                    if let Some(ref etype) = expected_type {
                        if SCRIPT_TRIGGER_TYPES.contains(&etype.as_str()) {
                            if script_result.is_some() {
                                return Ok(script_result.map(|md| Hover {
                                    contents: HoverContents::Markup(MarkupContent {
                                        kind: MarkupKind::Markdown,
                                        value: md,
                                    }),
                                    range: None,
                                }));
                            }
                        }
                    }

                    // If expected type matches a pack type, search that specific pack
                    let entity_info = if let Some(ref etype) = expected_type {
                        let defs = self.definitions.read().await;
                        // Look for the entity in the specific pack for this type
                        let key = format!("entity:{}", word);
                        defs.get(&key).and_then(|def| {
                            let pt = def.path.file_stem().and_then(|s| s.to_str()).unwrap_or("");
                            if pack_type_matches(pt, etype) {
                                let cd = def.path.parent().and_then(|p| p.parent()).map(|p| p.to_path_buf());
                                Some((pt.to_string(), cd))
                            } else {
                                None
                            }
                        })
                    } else {
                        // No context — search all packs
                        let defs = self.definitions.read().await;
                        defs.get(&format!("entity:{}", word)).map(|def| {
                            let pt = def.path.file_stem().and_then(|s| s.to_str()).unwrap_or("").to_string();
                            let cd = def.path.parent().and_then(|p| p.parent()).map(|p| p.to_path_buf());
                            (pt, cd)
                        })
                    };

                    if let Some((pack_type, content_dir)) = entity_info {
                        let config = content_dir.and_then(|d| {
                            let scripts = d.join("scripts");
                            if scripts.exists() {
                                read_entity_config(&word, &pack_type, &scripts)
                            } else {
                                None
                            }
                        });
                        Some(config.unwrap_or_else(|| format!("{} ({})", word, pack_type)))
                    } else {
                        script_result
                    }
                }
            }
        };

        let value = markdown.unwrap_or_else(|| word.clone());
        Ok(Some(Hover {
            contents: HoverContents::Markup(MarkupContent {
                kind: MarkupKind::Markdown,
                value,
            }),
            range: None,
        }))
    }

    async fn goto_definition(
        &self,
        params: GotoDefinitionParams,
    ) -> Result<Option<GotoDefinitionResponse>> {
        let uri = &params.text_document_position_params.text_document.uri;
        let pos = params.text_document_position_params.position;

        let Some(text) = self.documents.get(uri).map(|v| v.clone()) else {
            return Ok(None);
        };

        let word = word_at_position(&text, pos);
        if word.is_empty() {
            return Ok(None);
        }

        if word_prefix_at_position(&text, pos) == Some(b'$') {
            if let Some((line, col)) = find_local_var_definition(&text, &word, pos.line) {
                return Ok(Some(GotoDefinitionResponse::Scalar(Location {
                    uri: uri.clone(),
                    range: Range {
                        start: Position {
                            line,
                            character: col,
                        },
                        end: Position {
                            line,
                            character: col + word.len() as u32 + 1,
                        },
                    },
                })));
            }
        }

        let defs = self.definitions.read().await;

        // Try script (proc/label), then command, then entity, then constant
        let keys = [
            format!("script:{}", word),
            format!("command:{}", word),
            format!("entity:{}", word),
            format!("constant:{}", word),
        ];

        // If cursor is on a trigger header line, we're at the declaration — don't return self
        let at_declaration = text
            .lines()
            .nth(pos.line as usize)
            .map(|line| {
                let t = line.trim_start();
                t.starts_with('[') && t.contains(&format!(",{}]", word))
            })
            .unwrap_or(false);
        if at_declaration {
            return Ok(None);
        }

        for key in &keys {
            if let Some(def) = defs.get(key) {
                let target_uri = Url::from_file_path(&def.path).unwrap_or_else(|_| uri.clone());
                return Ok(Some(GotoDefinitionResponse::Scalar(Location {
                    uri: target_uri,
                    range: Range {
                        start: Position {
                            line: def.line,
                            character: 0,
                        },
                        end: Position {
                            line: def.line,
                            character: 0,
                        },
                    },
                })));
            }
        }

        Ok(None)
    }

    async fn references(&self, params: ReferenceParams) -> Result<Option<Vec<Location>>> {
        let uri = &params.text_document_position.text_document.uri;
        let pos = params.text_document_position.position;

        let Some(text) = self.documents.get(uri).map(|v| v.clone()) else {
            return Ok(None);
        };

        let word = word_at_position(&text, pos);
        if word.is_empty() {
            return Ok(None);
        }

        let sources = self.all_sources.read().await;
        let mut locations = Vec::new();

        for (path_str, source) in sources.iter() {
            for (line_num, line) in source.lines().enumerate() {
                if line.contains(&word) {
                    let path = PathBuf::from(path_str);
                    if let Ok(file_uri) = Url::from_file_path(&path) {
                        // Find exact column position(s) in this line
                        let mut start = 0;
                        while let Some(idx) = line[start..].find(&word) {
                            let col = start + idx;
                            // Verify it's a whole word match
                            let before_ok = col == 0
                                || !line.as_bytes()[col - 1].is_ascii_alphanumeric()
                                    && line.as_bytes()[col - 1] != b'_';
                            let after_idx = col + word.len();
                            let after_ok = after_idx >= line.len()
                                || !line.as_bytes()[after_idx].is_ascii_alphanumeric()
                                    && line.as_bytes()[after_idx] != b'_';
                            if before_ok && after_ok {
                                locations.push(Location {
                                    uri: file_uri.clone(),
                                    range: Range {
                                        start: Position {
                                            line: line_num as u32,
                                            character: col as u32,
                                        },
                                        end: Position {
                                            line: line_num as u32,
                                            character: (col + word.len()) as u32,
                                        },
                                    },
                                });
                            }
                            start = col + word.len();
                        }
                    }
                }
            }
        }

        if locations.is_empty() {
            Ok(None)
        } else {
            Ok(Some(locations))
        }
    }

    async fn code_action(
        &self,
        params: CodeActionParams,
    ) -> Result<Option<CodeActionResponse>> {
        let uri = &params.text_document.uri;
        let Some(text) = self.documents.get(uri).map(|v| v.clone()) else {
            return Ok(None);
        };
        let lines: Vec<&str> = text.lines().collect();
        let defs = self.definitions.read().await;
        let reg_guard = self.registry.read().await;
        let registry = reg_guard.as_ref();
        let mut actions: Vec<CodeActionOrCommand> = Vec::new();

        // A "test proc/label/command from production code" error has no valid
        // quick fix — the only remedy is moving the code, which the message
        // already states. Offer nothing here (not even extract), so the user
        // just sees "not allowed in production".
        if params
            .context
            .diagnostics
            .iter()
            .any(|d| d.message.contains("from production code"))
        {
            return Ok(None);
        }

        // ── Quick fixes derived from the diagnostics under the cursor ──
        for diag in &params.context.diagnostics {
            let msg = &diag.message;
            // Bare command used as a value — offer to call it with an argument
            // template built from the command's parameter types.
            if msg.contains("used here as a bare value") {
                if let Some(name) = first_quoted(msg) {
                    push_call_with_args(&mut actions, uri, &lines, diag, &name, registry);
                }
                continue;
            }
            let Some(raw) = first_quoted(msg) else {
                // Diagnostics without a quoted symbol still get type-mismatch handling below.
                if msg.starts_with("Type mismatch:") {
                    push_type_mismatch_fixes(&mut actions, uri, &lines, diag, msg);
                }
                continue;
            };
            // Strip any leading sigil (~ @ ^ % $) to get the bare name.
            let sigil = raw.chars().next().filter(|c| "~@^%$".contains(*c));
            let bare = match sigil {
                Some(_) => &raw[1..],
                None => &raw[..],
            };

            if msg.contains("cannot be resolved to a command.") {
                push_did_you_mean(&mut actions, uri, &lines, diag, &defs, "command:", bare, sigil);
            } else if msg.contains("cannot be resolved to a proc.") {
                push_did_you_mean(&mut actions, uri, &lines, diag, &defs, "script:", bare, sigil);
                push_create_script(&mut actions, uri, &text, bare, "proc");
            } else if msg.contains("cannot be resolved to a label.") {
                push_did_you_mean(&mut actions, uri, &lines, diag, &defs, "script:", bare, sigil);
                push_create_script(&mut actions, uri, &text, bare, "label");
            } else if msg.contains("cannot be resolved to a constant.") {
                push_did_you_mean(&mut actions, uri, &lines, diag, &defs, "constant:", bare, sigil);
            } else if msg.contains("could not be resolved to a symbol.") {
                // Could be an entity, command, or constant — try all, plus
                // offer to quote it as a string literal.
                push_did_you_mean(&mut actions, uri, &lines, diag, &defs, "entity:", bare, sigil);
                push_did_you_mean(&mut actions, uri, &lines, diag, &defs, "command:", bare, sigil);
                push_quote_as_string(&mut actions, uri, &lines, diag, bare);
            } else if msg.starts_with("Type mismatch:") {
                push_type_mismatch_fixes(&mut actions, uri, &lines, diag, msg);
            }
        }

        // ── Extract refactoring for a non-empty selection ──
        if params.range.start != params.range.end {
            if let Some(a) = build_extract_action(uri, &lines, params.range, "proc", registry) {
                actions.push(a);
            }
            if let Some(a) = build_extract_action(uri, &lines, params.range, "label", registry) {
                actions.push(a);
            }
        }

        if actions.is_empty() {
            Ok(None)
        } else {
            Ok(Some(actions))
        }
    }

    async fn inlay_hint(&self, params: InlayHintParams) -> Result<Option<Vec<InlayHint>>> {
        let uri = &params.text_document.uri;
        let range = params.range;

        let Some(text) = self.documents.get(uri).map(|v| v.clone()) else {
            return Ok(None);
        };

        let file_path = uri_to_path(uri);
        let mut lexer = Lexer::new(&text, &file_path);
        let Ok(tokens) = lexer.tokenize() else {
            return Ok(None);
        };

        let reg_guard = self.registry.read().await;
        let Some(ref registry) = *reg_guard else {
            return Ok(None);
        };
        let pnames = self.param_names.read().await;

        let mut hints = Vec::new();

        let mut i = 0;
        while i < tokens.len() {
            let tok = &tokens[i];
            let line0 = tok.line.saturating_sub(1) as u32;
            if line0 < range.start.line || line0 > range.end.line {
                i += 1;
                continue;
            }

            // Detect command(args) or ~proc(args)
            let (call_name, is_script) = match &tok.kind {
                TokenKind::Command | TokenKind::Identifier => {
                    if registry.lookup_command(&tok.value).is_some() {
                        (tok.value.as_str(), false)
                    } else {
                        i += 1;
                        continue;
                    }
                }
                TokenKind::ScriptCall => {
                    // ~ token, next token is the name
                    if i + 1 < tokens.len() {
                        let name_tok = &tokens[i + 1];
                        (name_tok.value.as_str(), true)
                    } else {
                        i += 1;
                        continue;
                    }
                }
                _ => {
                    i += 1;
                    continue;
                }
            };

            // Find the opening paren
            let paren_idx = if is_script { i + 2 } else { i + 1 };
            if paren_idx >= tokens.len() || tokens[paren_idx].kind != TokenKind::LParen {
                i += 1;
                continue;
            }

            // Get param names for this call
            let names = pnames.get(call_name);

            // Get return types for proc calls
            let return_types = if is_script {
                registry
                    .lookup_script(call_name)
                    .and_then(|sym| match &sym.kind {
                        runec::symbol::SymbolKind::Script { return_types, .. } => {
                            if return_types.is_empty() {
                                None
                            } else {
                                Some(return_types.clone())
                            }
                        }
                        _ => None,
                    })
            } else {
                None
            };

            // Walk arguments and generate parameter name hints
            if let Some(names) = names {
                let mut arg_idx = 0;
                let mut depth = 0;
                let mut j = paren_idx;
                while j < tokens.len() {
                    match tokens[j].kind {
                        TokenKind::LParen => {
                            depth += 1;
                            if depth == 1 && arg_idx < names.len() {
                                // Hint before the first argument
                                if j + 1 < tokens.len() && tokens[j + 1].kind != TokenKind::RParen
                                {
                                    let next = &tokens[j + 1];
                                    hints.push(InlayHint {
                                        position: Position {
                                            line: next.line.saturating_sub(1) as u32,
                                            character: next.column.saturating_sub(1) as u32,
                                        },
                                        label: InlayHintLabel::String(format!(
                                            "{}:",
                                            names[arg_idx].trim_start_matches('$')
                                        )),
                                        kind: Some(InlayHintKind::PARAMETER),
                                        padding_right: Some(true),
                                        padding_left: None,
                                        text_edits: None,
                                        tooltip: None,
                                        data: None,
                                    });
                                }
                            }
                        }
                        TokenKind::RParen => {
                            depth -= 1;
                            if depth == 0 {
                                // Return type hint after closing paren
                                if let Some(ref rtypes) = return_types {
                                    let label = rtypes
                                        .iter()
                                        .map(|t| t.to_string())
                                        .collect::<Vec<_>>()
                                        .join(", ");
                                    hints.push(InlayHint {
                                        position: Position {
                                            line: tokens[j].line.saturating_sub(1) as u32,
                                            character: tokens[j].column as u32,
                                        },
                                        label: InlayHintLabel::String(format!("-> {}", label)),
                                        kind: Some(InlayHintKind::TYPE),
                                        padding_left: Some(true),
                                        padding_right: None,
                                        text_edits: None,
                                        tooltip: None,
                                        data: None,
                                    });
                                }
                                break;
                            }
                        }
                        TokenKind::Comma if depth == 1 => {
                            arg_idx += 1;
                            if arg_idx < names.len() && j + 1 < tokens.len() {
                                let next = &tokens[j + 1];
                                hints.push(InlayHint {
                                    position: Position {
                                        line: next.line.saturating_sub(1) as u32,
                                        character: next.column.saturating_sub(1) as u32,
                                    },
                                    label: InlayHintLabel::String(format!(
                                        "{}:",
                                        names[arg_idx].trim_start_matches('$')
                                    )),
                                    kind: Some(InlayHintKind::PARAMETER),
                                    padding_right: Some(true),
                                    padding_left: None,
                                    text_edits: None,
                                    tooltip: None,
                                    data: None,
                                });
                            }
                        }
                        _ => {}
                    }
                    j += 1;
                }
                i = j + 1;
            } else {
                i += 1;
            }

            continue;
        }

        Ok(Some(hints))
    }

    async fn semantic_tokens_full(
        &self,
        params: SemanticTokensParams,
    ) -> Result<Option<SemanticTokensResult>> {
        let uri = &params.text_document.uri;
        let Some(text) = self.documents.get(uri).map(|v| v.clone()) else {
            return Ok(None);
        };

        let file_path = uri_to_path(uri);
        let mut lexer = Lexer::new(&text, &file_path);
        let Ok(tokens) = lexer.tokenize() else {
            return Ok(None);
        };

        let reg_guard = self.registry.read().await;
        let Some(ref registry) = *reg_guard else {
            return Ok(None);
        };

        let mut semantic_tokens: Vec<SemanticToken> = Vec::new();
        let mut prev_line: u32 = 0;
        let mut prev_start: u32 = 0;

        for token in &tokens {
            let token_type = match &token.kind {
                TokenKind::Identifier | TokenKind::Command => {
                    if registry.lookup_command(&token.value).is_some() {
                        Some(TT_COMMAND)
                    } else {
                        None
                    }
                }
                TokenKind::ScriptCall | TokenKind::JumpCall => Some(TT_PROC),
                _ => None,
            };

            let Some(tt) = token_type else { continue };

            let line = token.line.saturating_sub(1) as u32;
            let start = token.column.saturating_sub(1) as u32;
            let length = token.value.len() as u32;

            let delta_line = line - prev_line;
            let delta_start = if delta_line == 0 {
                start - prev_start
            } else {
                start
            };

            semantic_tokens.push(SemanticToken {
                delta_line,
                delta_start,
                length,
                token_type: tt,
                token_modifiers_bitset: 0,
            });

            prev_line = line;
            prev_start = start;
        }

        Ok(Some(SemanticTokensResult::Tokens(SemanticTokens {
            result_id: None,
            data: semantic_tokens,
        })))
    }
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::from_default_env()
                .add_directive("rs_lsp=info".parse().unwrap()),
        )
        .with_writer(std::io::stderr)
        .init();

    info!("Starting RuneScript 2 LSP server");

    let stdin = tokio::io::stdin();
    let stdout = tokio::io::stdout();

    let (service, socket) = LspService::new(Backend::new);
    Server::new(stdin, stdout, socket).serve(service).await;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_quoted_extracts_name() {
        assert_eq!(
            first_quoted("'badcmd' cannot be resolved to a command.").as_deref(),
            Some("badcmd")
        );
        assert_eq!(
            first_quoted("'~myproc' cannot be resolved to a proc.").as_deref(),
            Some("~myproc")
        );
        assert_eq!(first_quoted("no quotes here"), None);
    }

    #[test]
    fn levenshtein_basic() {
        assert_eq!(levenshtein("kitten", "sitting"), 3);
        assert_eq!(levenshtein("mes", "mes"), 0);
        assert_eq!(levenshtein("mesbox", "mesb0x"), 1);
    }

    #[test]
    fn fuzzy_matches_ranks_by_distance() {
        let mut defs: DefIndex = std::collections::HashMap::new();
        for n in ["mes", "mesbox", "chatnpc", "npc_say"] {
            defs.insert(
                format!("command:{n}"),
                DefinitionLocation { path: PathBuf::new(), line: 0 },
            );
        }
        // "mesbax" should suggest "mesbox" (distance 1) before others.
        let got = fuzzy_matches(&defs, "command:", "mesbax", 5);
        assert_eq!(got.first().map(|s| s.as_str()), Some("mesbox"));
        // exact name is excluded
        assert!(!fuzzy_matches(&defs, "command:", "mes", 5).contains(&"mes".to_string()));
    }

    #[test]
    fn end_position_handles_trailing_newline() {
        assert_eq!(end_position("a\n"), Position { line: 1, character: 0 });
        assert_eq!(end_position("a"), Position { line: 0, character: 1 });
        assert_eq!(end_position("a\nbc"), Position { line: 1, character: 2 });
    }

    #[test]
    fn find_name_range_locates_whole_word() {
        let lines = vec!["~myproc(5)"];
        let diag = Diagnostic {
            range: Range {
                start: Position { line: 0, character: 0 },
                end: Position { line: 0, character: 10 },
            },
            ..Default::default()
        };
        // With sigil '~', the match is the `~myproc` occurrence (col 1..7).
        let r = find_name_range(&lines, &diag, "myproc", Some('~')).unwrap();
        assert_eq!(r.start.character, 1);
        assert_eq!(r.end.character, 7);
    }

    #[test]
    fn find_name_range_skips_variable_with_same_name() {
        // `def_int $coordx = coordx;` — the bare command fix (sigil None) must
        // target the RHS `coordx`, not the `$coordx` variable name.
        let lines = vec!["def_int $coordx = coordx;"];
        let diag = Diagnostic {
            range: Range {
                start: Position { line: 0, character: 0 },
                end: Position { line: 0, character: 25 },
            },
            ..Default::default()
        };
        let r = find_name_range(&lines, &diag, "coordx", None).unwrap();
        // `$coordx` starts at index 9 (the `coordx` at col 9 is preceded by `$`),
        // the command `coordx` is at col 18.
        assert_eq!(r.start.character, 18);
        assert_eq!(r.end.character, 24);
    }

    #[test]
    fn placeholder_literals_are_valid_defaults() {
        use runec::types::Type;
        assert_eq!(placeholder_literal(Type::String), "\"\"");
        assert_eq!(placeholder_literal(Type::Int), "0");
        assert_eq!(placeholder_literal(Type::Boolean), "false");
        assert_eq!(placeholder_literal(Type::Long), "0");
        // Named integer-base types fall back to 0 (widens to the named type).
        assert_eq!(placeholder_literal(Type::Coord), "0");
        assert_eq!(placeholder_literal(Type::Obj), "0");
    }

    #[test]
    fn free_vars_excludes_locally_declared() {
        let body = "def_int $mult = ~wc_hatchet_power($hatchet)\nif ($hatchet = dragon_hatchet) {\n  return($mult)\n}";
        let got = free_vars(body);
        // $hatchet is used but never declared in the body → a parameter.
        // $mult is declared (def_int) → not a parameter.
        assert_eq!(got, vec!["hatchet".to_string()]);
    }

    #[test]
    fn find_var_type_from_param_and_def() {
        let header = "[proc,wc_hatchet_power](obj $hatchet)(int)\ndef_int $mult = 0";
        assert_eq!(find_var_type(header, "hatchet").as_deref(), Some("obj"));
        assert_eq!(find_var_type(header, "mult").as_deref(), Some("int"));
        // A pure usage (no preceding type) yields nothing.
        assert_eq!(find_var_type("return($hatchet)", "hatchet"), None);
    }

    #[test]
    fn first_return_values_parses_args() {
        let body = "if ($x = 1) {\n  return(385)\n}\nreturn(100)";
        assert_eq!(first_return_values(body), Some(vec!["385".to_string()]));
        let multi = "return(coord, $idx)";
        assert_eq!(
            first_return_values(multi),
            Some(vec!["coord".to_string(), " $idx".to_string()])
        );
    }

    #[test]
    fn infer_value_type_handles_literals_and_vars() {
        let region = "def_obj $hatchet = 0";
        assert_eq!(infer_value_type("385", region, None), "int");
        assert_eq!(infer_value_type("\"hi\"", region, None), "string");
        assert_eq!(infer_value_type("true", region, None), "boolean");
        assert_eq!(infer_value_type("$hatchet", region, None), "obj");
        // unknown bare identifier with no registry → defaults to int
        assert_eq!(infer_value_type("mystery", region, None), "int");
    }

    #[test]
    fn find_enclosing_header_scans_upward() {
        let lines = vec![
            "[proc,foo](obj $x)(int)",
            "def_int $y = 1",
            "return($y)",
        ];
        assert_eq!(find_enclosing_header(&lines, 2), Some(0));
        assert_eq!(find_enclosing_header(&lines, 0), Some(0));
    }
}
