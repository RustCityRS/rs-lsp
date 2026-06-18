package rs2.lsp

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class Rs2ConfigParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = Rs2ConfigLexer()

    override fun createParser(project: Project?): PsiParser {
        return PsiParser { root: IElementType, builder: PsiBuilder ->
            val marker = builder.mark()
            while (!builder.eof()) {
                val tt = builder.tokenType
                if (tt == Rs2ConfigTokenTypes.SECTION_NAME || tt == Rs2ConfigTokenTypes.PACK_NAME) {
                    // Wrap a declaration name — a config `[section]` header or a
                    // pack `id=name` entry — in a SECTION composite so it becomes
                    // a PsiNameIdentifierOwner (a navigable declaration).
                    val section = builder.mark()
                    builder.advanceLexer()
                    section.done(Rs2ConfigTokenTypes.SECTION)
                } else {
                    builder.advanceLexer()
                }
            }
            marker.done(root)
            builder.treeBuilt
        }
    }

    override fun getFileNodeType(): IFileElementType = Rs2ConfigTokenTypes.FILE

    override fun getWhitespaceTokens(): TokenSet = Rs2ConfigTokenTypes.WHITESPACES

    override fun getCommentTokens(): TokenSet = Rs2ConfigTokenTypes.COMMENTS

    override fun getStringLiteralElements(): TokenSet = Rs2ConfigTokenTypes.STRINGS

    override fun createElement(node: ASTNode): PsiElement =
        if (node.elementType == Rs2ConfigTokenTypes.SECTION) {
            Rs2ConfigSectionElement(node)
        } else {
            LeafPsiElement(node.elementType, node.text)
        }

    override fun createFile(viewProvider: FileViewProvider): PsiFile = Rs2ConfigFile(viewProvider)
}
