package rs2.lsp

import com.intellij.lang.Language

/**
 * Language for RuneScript 2 *support* files — the config/pack family that shares
 * the `[section]` + `key=value` grammar (.obj/.npc/.loc/.dbtable/.anim/…), the
 * `id=name` pack files (.pack/.order/.hashes) and the `^name = value` .constant
 * files. Separate from [Rs2Language] (the .rs2 scripting language) because these
 * have a different, much simpler grammar and no LSP backing — highlighting only.
 */
object Rs2ConfigLanguage : Language("RS2Config") {
    private fun readResolve(): Any = Rs2ConfigLanguage
}
