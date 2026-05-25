package rs2.lsp

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.lang.PsiBuilder
import com.intellij.psi.tree.IElementType

class Rs2ParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = Rs2Lexer()

    override fun createParser(project: Project?): PsiParser {
        return PsiParser { root: IElementType, builder: PsiBuilder ->
            val marker = builder.mark()
            while (!builder.eof()) {
                builder.advanceLexer()
            }
            marker.done(root)
            builder.treeBuilt
        }
    }

    override fun getFileNodeType(): IFileElementType = Rs2TokenTypes.FILE

    override fun getWhitespaceTokens(): TokenSet = Rs2TokenTypes.WHITESPACES

    override fun getCommentTokens(): TokenSet = Rs2TokenTypes.COMMENTS

    override fun getStringLiteralElements(): TokenSet = Rs2TokenTypes.STRINGS

    override fun createElement(node: ASTNode): PsiElement = LeafPsiElement(node.elementType, node.text)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = Rs2File(viewProvider)
}
