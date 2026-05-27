package rs2.lsp

import com.intellij.ide.IconProvider
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.IconUtil
import javax.swing.Icon

class Rs2IconProvider : IconProvider() {
    companion object {
        private val ICONS = mapOf(
            "obj" to "/icons/obj.png",
            "npc" to "/icons/npc.png",
            "loc" to "/icons/loc.png",
            "inv" to "/icons/inv.png",
            "seq" to "/icons/seq.png",
            "spotanim" to "/icons/spotanim.png",
            "constant" to "/icons/constant.png",
            "varp" to "/icons/varp.png",
            "varn" to "/icons/varn.png",
            "vars" to "/icons/vars.png",
            "varbit" to "/icons/varbit.png",
            "enum" to "/icons/enum.png",
            "struct" to "/icons/struct.png",
            "param" to "/icons/param.png",
            "hunt" to "/icons/hunt.png",
            "synth" to "/icons/synth.png",
            "category" to "/icons/category.png",
            "idk" to "/icons/idk.png",
            "interface" to "/icons/interface.png",
            "if" to "/icons/interface.png",
            "component" to "/icons/component.png",
            "dbrow" to "/icons/dbrow.png",
            "dbtable" to "/icons/dbtable.png",
            "dbcolumn" to "/icons/dbcolumn.png",
            "mesanim" to "/icons/mesanim.png",
            "midi" to "/icons/midi.png",
            "jingle" to "/icons/jingle.png",
            "stat" to "/icons/stat.png",
            "model" to "/icons/model.png",
            "fontmetrics" to "/icons/fontmetrics.png",
            "pack" to "/icons/pack.png",
        )

        private val iconCache = mutableMapOf<String, Icon>()

        fun getIconForExtension(ext: String): Icon? {
            val path = ICONS[ext] ?: return null
            return iconCache.getOrPut(ext) {
                try {
                    val raw = IconLoader.getIcon(path, Rs2IconProvider::class.java)
                    IconUtil.scale(raw, null, 0.375f)
                } catch (_: Exception) {
                    return null
                }
            }
        }
    }

    override fun getIcon(element: PsiElement, flags: Int): Icon? {
        if (element is PsiFile) {
            val ext = element.virtualFile?.extension ?: return null
            return getIconForExtension(ext)
        }
        return null
    }
}
