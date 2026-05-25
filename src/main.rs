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

#[derive(Debug)]
struct Backend {
    client: Client,
    documents: Arc<DashMap<Url, String>>,
    registry: Arc<RwLock<Option<SymbolRegistry>>>,
    scripts_dir: Arc<RwLock<Option<PathBuf>>>,
    all_compiled: Arc<RwLock<Vec<CompiledScript>>>,
    all_sources: Arc<RwLock<std::collections::HashMap<String, String>>>,
    definitions: Arc<RwLock<DefIndex>>,
    change_version: Arc<AtomicU64>,
    fast_diagnostics: Arc<DashMap<Url, Vec<Diagnostic>>>,
}

impl Backend {
    fn new(client: Client) -> Self {
        Self {
            client,
            documents: Arc::new(DashMap::new()),
            registry: Arc::new(RwLock::new(None)),
            scripts_dir: Arc::new(RwLock::new(None)),
            all_compiled: Arc::new(RwLock::new(Vec::new())),
            all_sources: Arc::new(RwLock::new(std::collections::HashMap::new())),
            definitions: Arc::new(RwLock::new(std::collections::HashMap::new())),
            change_version: Arc::new(AtomicU64::new(0)),
            fast_diagnostics: Arc::new(DashMap::new()),
        }
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

        // Re-register + type check
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
                        DiagnosticSeverity::WARNING,
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
            checker.check_file(&file, &file_path);
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

    let mut rs2_files: Vec<PathBuf> = Vec::new();
    collect_rs2_files(&scripts_dir, &mut rs2_files);
    rs2_files.sort();

    let mut defs: DefIndex = std::collections::HashMap::new();

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
            // Index definition location for go-to-definition
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

    info!("Registry initialized");

    *scripts_dir_lock.write().await = Some(scripts_dir);
    *registry_lock.write().await = Some(registry);

    // Notify client to re-request semantic tokens now that the registry is ready
    client.semantic_tokens_refresh().await.ok();

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
                        DiagnosticSeverity::WARNING,
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
            checker.check_file(&file, &file_path);
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

fn word_at_position(text: &str, pos: Position) -> String {
    let Some(line) = text.lines().nth(pos.line as usize) else {
        return String::new();
    };
    let col = pos.character as usize;
    let bytes = line.as_bytes();
    if col >= bytes.len() {
        return String::new();
    }

    let is_word_char = |b: u8| b.is_ascii_alphanumeric() || b == b'_';

    // Expand left
    let mut start = col;
    while start > 0 && is_word_char(bytes[start - 1]) {
        start -= 1;
    }
    // Skip leading ~ or @ or ^ or $ or %
    let prefix_start = if start > 0 && matches!(bytes[start - 1], b'~' | b'@' | b'^' | b'$' | b'%')
    {
        start - 1
    } else {
        start
    };
    let _ = prefix_start; // we only want the bare word for lookup

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

fn uri_to_path(uri: &Url) -> PathBuf {
    uri.to_file_path()
        .unwrap_or_else(|_| PathBuf::from(uri.path()))
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
                definition_provider: Some(OneOf::Left(true)),
                references_provider: Some(OneOf::Left(true)),
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
            self.diagnose(&uri, &change.text).await;
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

        let defs = self.definitions.read().await;

        // Try script (proc/label), then command, then entity, then constant
        let keys = [
            format!("script:{}", word),
            format!("command:{}", word),
            format!("entity:{}", word),
            format!("constant:{}", word),
        ];

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
