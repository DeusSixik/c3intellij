package org.c3lang.intellij;

import com.intellij.lang.ASTNode;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import org.c3lang.intellij.annotation.fix.AddDynamicAttributeFix;
import org.c3lang.intellij.annotation.fix.AddSelfParameterFix;
import org.c3lang.intellij.annotation.fix.ImplementInterfaceMethodsFix;
import org.c3lang.intellij.index.InterfaceService;
import org.c3lang.intellij.index.NameIndexService;
import org.c3lang.intellij.psi.*;
import org.c3lang.intellij.types.CallChecker;
import org.c3lang.intellij.types.DuplicateChecker;
import org.c3lang.intellij.types.InferredType;
import org.c3lang.intellij.types.TypeChecker;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// TODO move to kotlin after first NPE
public class C3Annotator implements Annotator
{
    private C3Annotator() {}

    public static final C3Annotator INSTANCE = new C3Annotator();

    private static TextAttributesKey colorForTypeDefiniton(PsiElement definition)
    {
        if (definition instanceof C3StructDeclaration)
        {
            return C3SyntaxHighlighter.STRUCT_NAME_KEY;
        }
        else if (definition instanceof C3EnumDeclaration)
        {
            return C3SyntaxHighlighter.ENUM_NAME_KEY;
        }
        else if (definition instanceof C3AliasTypeDecl)
        {
            return C3SyntaxHighlighter.ALIAS_TYPE_NAME_KEY;
        }
        else if (definition instanceof C3TypedefDecl)
        {
            return C3SyntaxHighlighter.TYPEDEF_NAME_KEY;
        }
        else if (definition instanceof C3BitstructDeclaration)
        {
            return C3SyntaxHighlighter.BITSTRUCT_NAME_KEY;
        }
        return C3SyntaxHighlighter.TYPE_DEFINITION_KEY;
    }

    private void annotate(@NotNull C3AliasDecl element, @NotNull AnnotationHolder annotationHolder)
    {
        annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                                    .textAttributes(C3SyntaxHighlighter.ALIAS_NAME_KEY).range(element.getAliasName()).create();
        C3AliasDeclarationSource source = element.getAliasDeclarationSource();
        assert source != null;
        boolean is_ident = element.getAliasName().getNode().findChildByType(C3Types.IDENT) != null;
        boolean is_at_ident = !is_ident && element.getAliasName().getNode().findChildByType(C3Types.AT_IDENT) != null;
        boolean is_const = !is_at_ident && !is_ident;

        if (source.getPathConst() != null && !is_const)
        {
            annotationHolder.newAnnotation(HighlightSeverity.ERROR, "An alias of a constant must also be all caps.")
                            .range(element.getAliasName())
                            .create();
        }
        if (source.getPathIdent() != null)
        {
            if (is_const)
            {
                annotationHolder.newAnnotation(HighlightSeverity.ERROR, "A const alias may not alias non-const identifiers.")
                                .range(source.getPathIdent())
                                .create();
            }
            else if (is_at_ident)
            {
                annotationHolder.newAnnotation(HighlightSeverity.ERROR, "An @-alias alias may not alias non-@ identifiers.")
                                .range(source.getPathIdent())
                                .create();
            }
        }
        if (source.getPathAtIdent() != null)
        {
            if (is_const)
            {
                annotationHolder.newAnnotation(HighlightSeverity.ERROR, "A const alias may not alias an @-identifiers.")
                                .range(source.getPathAtIdent())
                                .create();
            }
            else if (is_ident)
            {
                annotationHolder.newAnnotation(HighlightSeverity.ERROR, "An @-alias may not alias non-@ identifiers.")
                                .range(source.getPathAtIdent())
                                .create();
            }
        }
    }

    private static boolean is_valid_hex(char c)
    {
        return c >= '0' && c <= 'f' && (c <= '9' || c >= 'A') && (c <= 'F' || c >= 'a');
    }

    private static boolean is_valid_b64(char c)
    {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/' ||
               c == '=';
    }

    private void annotateBytesError(@NotNull AnnotationHolder annotationHolder, String error, ASTNode element, int start, int end)
    {
        int startOffset = element.getTextRange().getStartOffset();
        annotationHolder.newAnnotation(HighlightSeverity.ERROR, error)
                        .textAttributes(C3SyntaxHighlighter.INVALID_ESCAPE_SEQ_KEY)
                        .range(new TextRange(startOffset + start, startOffset + end)).create();

    }

    private void annotateBytes(@NotNull C3BytesExpr bytesExpr, @NotNull AnnotationHolder annotationHolder)
    {
        ASTNode element = bytesExpr.getNode().findChildByType(C3TokenSets.BYTES);
        assert(element != null);
        String text = element.getText();
        assert(text.length() > 3);
        boolean is_hex = text.charAt(0) == 'x';
        int start_offset = is_hex ? 2 : 4;
        char type = text.charAt(start_offset - 1);
        int end = text.length() - 1;
        int index = start_offset;
        int digits = 0;
        boolean has_error = false;
        int error_start = -1;
        String error = is_hex ? "Hex character expected." : "Invalid base 64 character.";
        while (index < end)
        {
            char c = text.charAt(index);
            if (c == type)
            {
                if (error_start > -1) annotateBytesError(annotationHolder, error, element, error_start, index);
                error_start = -1;
                index++;
                while (text.charAt(index) != type) index++;
                index++;
                continue;
            }
            index++;
            if (Character.isWhitespace(c))
            {
                if (error_start > -1) annotateBytesError(annotationHolder, error, element, error_start, index - 1);
                error_start = -1;
                continue;
            }
            if (is_hex)
            {
                if (!is_valid_hex(c))
                {
                    if (error_start < 0) error_start = index - 1;
                    has_error = true;
                    continue;
                }
                if (error_start > -1) annotateBytesError(annotationHolder, error, element, error_start, index - 1);
                error_start = -1;
                digits++;
                continue;
            }
            if (!is_valid_b64(c))
            {
                if (error_start < 0) error_start = index - 1;
                has_error = true;
                continue;
            }
            if (error_start > -1) annotateBytesError(annotationHolder, error, element, error_start, index - 1);
            error_start = -1;
        }
        if (error_start > -1) annotateBytesError(annotationHolder, error, element, error_start, index - 1);
        if (!has_error && digits % 2 != 0)
        {
            annotationHolder.newAnnotation(HighlightSeverity.ERROR, "An even number of hex characters is required.")
                            .textAttributes(C3SyntaxHighlighter.INVALID_ESCAPE_SEQ_KEY)
                            .range(element).create();

        }
    }
    private void annotateString(@NotNull C3StringExpr stringExpr, @NotNull AnnotationHolder annotationHolder)
    {

        for (ASTNode element : stringExpr.getNode().getChildren(TokenSet.create(C3Types.STRING_LIT, C3Types.CHAR_LIT)))
        {
            String text = element.getText();
            assert(text.length() > 1);
            // No checking of raw strings.
            if (text.charAt(0) == '`') continue;
            int len = text.length();
            int start_range_offset = element.getTextRange().getStartOffset();
            for (int i = 1; i < len - 1; i++)
            {
                char c = text.charAt(i);
                if (c < ' ')
                {
                    annotationHolder.newAnnotation(HighlightSeverity.ERROR, "Illegal character")
                                    .textAttributes(C3SyntaxHighlighter.INVALID_ESCAPE_SEQ_KEY)
                                    .range(new TextRange(start_range_offset + i, start_range_offset+ i + 1)).create();
                    continue;
                }
                if (c == '\\')
                {
                    i++;
                    c = text.charAt(i);
                    if (c < ' ')
                    {
                        annotationHolder.newAnnotation(HighlightSeverity.ERROR, "Illegal character")
                                        .textAttributes(C3SyntaxHighlighter.INVALID_ESCAPE_SEQ_KEY)
                                        .range(new TextRange(start_range_offset + i, start_range_offset + i + 1)).create();
                    }
                    int checked_follow;
                    switch (c)
                    {
                        case 'x':
                            checked_follow = 2;
                            break;
                        case 'u':
                            checked_follow = 4;
                            break;
                        case 'U':
                            checked_follow = 8;
                            break;
                        case 'a':
                        case 'b':
                        case 'e':
                        case 'f':
                        case 'n':
                        case 'r':
                        case 't':
                        case 'v':
                        case '"':
                        case '\'':
                        case '\\':
                        case '0':
                            annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                                            .range(new TextRange(start_range_offset + i - 1, start_range_offset + i + 1))
                                            .textAttributes(C3SyntaxHighlighter.ESCAPE_SEQ_KEY).create();
                            continue;
                        default:
                            annotationHolder.newAnnotation(HighlightSeverity.ERROR, "Invalid escape sequence.")
                                            .textAttributes(C3SyntaxHighlighter.INVALID_ESCAPE_SEQ_KEY)
                                            .range(new TextRange(start_range_offset + i - 1, start_range_offset + i + 1))
                                            .create();
                            continue;
                    }
                    i++;
                    boolean ok_sequence = true;
                    for (int j = 0; j < checked_follow; j++)
                    {
                        char hex = text.charAt(i + j);
                        if (!is_valid_hex(hex))
                        {
                            annotationHolder.newAnnotation(HighlightSeverity.ERROR, "Unexpected character")
                                            .textAttributes(C3SyntaxHighlighter.INVALID_ESCAPE_SEQ_KEY)
                                            .range(new TextRange(start_range_offset + i - 2, start_range_offset + i + j))
                                            .create();
                            ok_sequence = false;
                            break;
                        }
                    }
                    if (ok_sequence)
                    {
                        annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                                        .range(new TextRange(start_range_offset + i - 2, start_range_offset + i + checked_follow))
                                        .textAttributes(C3SyntaxHighlighter.ESCAPE_SEQ_KEY).create();
                        i += checked_follow;
                    }

                }
            }
        }
    }

    @Override public void annotate(@NotNull PsiElement psiElement, @NotNull AnnotationHolder annotationHolder)
    {
        if (psiElement instanceof C3BytesExpr expr)
        {
            annotateBytes(expr, annotationHolder);
        }
        else if (psiElement instanceof C3StringExpr expr)
        {
            annotateString(expr, annotationHolder);
        }
        else if (psiElement instanceof C3AliasDecl element)
        {
            annotate(element, annotationHolder);
        }
        else if (psiElement instanceof C3AttrdefDecl element)
        {
            annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                                        .textAttributes(C3SyntaxHighlighter.ATTRDEF_ATTRIBUTE_KEY).range(element.getAttributeUserName()).create();
        }
        else if (psiElement instanceof C3AliasTypeDecl element)
        {
            annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                                        .textAttributes(C3SyntaxHighlighter.ALIAS_TYPE_NAME_KEY).range(element.getTypeName()).create();
        }
        else if (psiElement instanceof C3TypedefDecl element)
        {
            annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                                        .textAttributes(C3SyntaxHighlighter.TYPEDEF_NAME_KEY).range(element.getTypeName()).create();
        }
        else if (psiElement instanceof C3Type || psiElement instanceof C3OptionalType)
        {
            PsiElement parent = psiElement.getParent();
            if (parent instanceof C3FuncName || parent instanceof C3MacroName || parent instanceof C3OptionalType) return;
            if (psiElement instanceof C3Type c3Type && c3Type.getBaseType() != null && c3Type.getBaseType().getPath() != null)
            {
                C3Path path = c3Type.getBaseType().getPath();
                TextRange range = new TextRange(path.getTextRange().getEndOffset(), c3Type.getTextRange().getEndOffset());
                annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES).textAttributes(C3SyntaxHighlighter.TYPE_KEY).range(range).create();
            }
            else
            {
                annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES).textAttributes(C3SyntaxHighlighter.TYPE_KEY).create();
            }
        }
        else if (psiElement instanceof C3AttributeName)
        {
            annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES).textAttributes(C3SyntaxHighlighter.ATTRIBUTE_KEY).create();
        }
        else if (psiElement instanceof C3FuncName element)
        {
            if (element.getType() != null)
            {
                annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES).textAttributes(C3SyntaxHighlighter.TYPE_KEY).range(element.getType()).create();
                PsiElement last = element.getLastChild();
                if (last != null)
                {
                    annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES).textAttributes(C3SyntaxHighlighter.METHOD_KEY).range(last).create();
                }
            }
            else
            {
                annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES).textAttributes(C3SyntaxHighlighter.FUNCTION_KEY).create();
            }
        }
        else if (psiElement instanceof C3MacroName element)
        {

            TextAttributesKey color = C3SyntaxHighlighter.MACRO_KEY;
            String name = psiElement.getLastChild().getText();
            boolean at_macro = name != null && !name.isEmpty() && name.charAt(0) == '@';
            if (element.getType() != null)
            {
                color = at_macro ? C3SyntaxHighlighter.AT_MACRO_METHOD_KEY : C3SyntaxHighlighter.MACRO_METHOD_KEY;
            }
            else if (at_macro)
            {
                color = C3SyntaxHighlighter.AT_MACRO_KEY;
            }
            annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES).textAttributes(color).create();
        }
        else if (psiElement instanceof C3TypeName)
        {
            PsiElement parent = psiElement.getParent();
            if (parent == null) return;
            annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES).textAttributes(colorForTypeDefiniton(parent)).create();
        }
        else if (psiElement instanceof C3ModulePath || psiElement instanceof C3ImportPath)
        {
            annotateModulePath(psiElement, annotationHolder);
        }
        else if (psiElement instanceof C3Path path)
        {
            annotatePath(path, annotationHolder);
        }
        else if (psiElement instanceof C3Parameter param)
        {
            annotateParameter(param, annotationHolder);
        }
        else if (psiElement instanceof C3LocalDeclAfterType localDecl)
        {
            PsiElement ident = localDecl.getNameIdentElement();
            if (ident != null)
            {
                annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                    .textAttributes(C3SyntaxHighlighter.LOCAL_VARIABLE_KEY).range(ident).create();
            }
        }
        else if (psiElement instanceof C3VarDecl varDecl)
        {
            ASTNode ident = varDecl.getNode().findChildByType(C3Types.IDENT);
            if (ident != null)
            {
                annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                    .textAttributes(C3SyntaxHighlighter.LOCAL_VARIABLE_KEY).range(ident.getTextRange()).create();
            }
        }
        else if (psiElement instanceof C3ForeachVar foreachVar)
        {
            ASTNode ident = foreachVar.getNode().findChildByType(C3Types.IDENT);
            if (ident != null)
            {
                annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                    .textAttributes(C3SyntaxHighlighter.LOCAL_VARIABLE_KEY).range(ident.getTextRange()).create();
            }
        }
        else if (psiElement instanceof C3IdentifierList identList && psiElement.getParent() instanceof C3StructMemberDeclaration)
        {
            for (ASTNode node : identList.getNode().getChildren(null))
            {
                if (node.getElementType() == C3Types.IDENT)
                {
                    annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                        .textAttributes(C3SyntaxHighlighter.FIELD_KEY).range(node.getTextRange()).create();
                }
            }
        }
        else if (psiElement instanceof C3StructMemberDeclaration memberDecl)
        {
            if (memberDecl.getIdentifierList() == null)
            {
                ASTNode ident = memberDecl.getNode().findChildByType(C3Types.IDENT);
                if (ident != null)
                {
                    annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                        .textAttributes(C3SyntaxHighlighter.FIELD_KEY).range(ident.getTextRange()).create();
                }
            }
        }
        else if (psiElement instanceof C3AccessIdent accessIdent)
        {
            annotateAccessIdent(accessIdent, annotationHolder);
        }
        else if (psiElement instanceof C3PathIdent pathIdent)
        {
            annotatePathIdent(pathIdent, annotationHolder);
        }
        else if (psiElement instanceof C3LocalDeclarationStmt localDecl)
        {
            annotateLocalDeclaration(localDecl, annotationHolder);
        }
        else if (psiElement instanceof C3BinaryExpr binaryExpr)
        {
            annotateAssignment(binaryExpr, annotationHolder);
        }
        else if (psiElement instanceof C3ReturnStmt returnStmt)
        {
            annotateReturnStmt(returnStmt, annotationHolder);
        }
        else if (psiElement instanceof C3CallExpr callExpr)
        {
            CallChecker.checkCall(callExpr, annotationHolder);
        }
        else if (psiElement instanceof C3StructDeclaration structDecl)
        {
            annotateStructDeclaration(structDecl, annotationHolder);
        }
        else if (psiElement instanceof C3InterfaceImpl impl)
        {
            annotateInterfaceImpl(impl, annotationHolder);
        }
        else if (psiElement instanceof C3FuncDef funcDef)
        {
            annotateFuncDef(funcDef, annotationHolder);
        }
        else if (psiElement instanceof C3MacroDefinition macroDef)
        {
            DuplicateChecker.checkMacro(macroDef, annotationHolder);
            annotateMacroDefinition(macroDef, annotationHolder);
        }
        else if (psiElement instanceof C3UnaryExpr unaryExpr)
        {
            annotateCast(unaryExpr, annotationHolder);
        }
    }

    private void annotateStructDeclaration(@NotNull C3StructDeclaration structDecl, @NotNull AnnotationHolder holder)
    {
        C3TypeName typeName = structDecl.getTypeName();
        C3StructBody body = structDecl.getStructBody();
        if (body != null && body.getStructMemberDeclarationList().isEmpty())
        {
            PsiElement anchor = typeName.getNameIdentifier() != null ? typeName.getNameIdentifier() : typeName;
            holder.newAnnotation(HighlightSeverity.ERROR, "Zero sized structs are not permitted.")
                .range(anchor)
                .create();
        }

        Project project = structDecl.getProject();
        if (DumbService.isDumb(project)) return;

        String structName = typeName.getText().strip();
        FullyQualifiedName structFqn = new FullyQualifiedName(ModuleName.from(structDecl), structName);
        for (FullyQualifiedName iface : InterfaceService.INSTANCE.getImplementedInterfaces(structFqn, project))
        {
            List<C3FuncDef> missing =
                InterfaceService.INSTANCE.findMissingInterfaceMethods(structFqn, iface, project);
            for (C3FuncDef missed : missing)
            {
                PsiElement anchor = contractAnchor(structDecl, iface, typeName);
                var annotation = holder.newAnnotation(
                        HighlightSeverity.ERROR,
                        "Struct '" + structName + "' does not implement interface method '"
                            + iface.getFullName() + "." + missed.getNameIdent() + "'.")
                    .range(anchor);
                if (missed == missing.get(0))
                {
                    annotation.withFix(new ImplementInterfaceMethodsFix(structDecl, iface));
                }
                annotation.create();
            }
        }
    }

    private static @NotNull PsiElement contractAnchor(
            @NotNull C3StructDeclaration structDecl,
            @NotNull FullyQualifiedName iface,
            @NotNull C3TypeName fallback)
    {
        C3InterfaceImpl impl = structDecl.getInterfaceImpl();
        if (impl != null)
        {
            if (isContractText(impl.getTypeName().getText(), iface)) return impl.getTypeName();
            for (C3Type contractType : impl.getTypeList())
            {
                if (isContractText(contractType.getText(), iface)) return contractType;
            }
        }
        return fallback.getNameIdentifier() != null ? fallback.getNameIdentifier() : fallback;
    }

    private static boolean isContractText(@NotNull String text, @NotNull FullyQualifiedName iface)
    {
        String clean = text.strip();
        return clean.equals(iface.getFullName())
            || clean.equals(iface.getName())
            || clean.endsWith("::" + iface.getName());
    }

    private void annotateFuncDef(@NotNull C3FuncDef funcDef, @NotNull AnnotationHolder holder)
    {
        annotateReturnCoverage(funcDef, holder);
        DuplicateChecker.checkFunction(funcDef, holder);

        String ownerText = InterfaceService.methodOwnerTypeName(funcDef);
        if (ownerText == null) return;
        if (PsiTreeUtil.getParentOfType(funcDef, C3InterfaceBody.class) != null) return;

        Project project = funcDef.getProject();
        if (DumbService.isDumb(project)) return;

        String implName = funcDef.getNameIdent();
        if (implName == null) return;

        if (!InterfaceService.firstParameterMatchesOwner(funcDef, ownerText))
        {
            String ownerShort = InterfaceService.shortName(ownerText);
            String paramName = freeSelfParameterName(funcDef);
            String example = paramName.equals("self") ? "&self" : ownerShort + "* " + paramName;
            PsiElement anchor = funcDef.getNameIdentifier() != null ? funcDef.getNameIdentifier() : funcDef;
            holder.newAnnotation(
                    HighlightSeverity.ERROR,
                    "Method '" + ownerText + "." + implName + "' must start with an argument of type '"
                        + ownerText + "', e.g. '" + example + "'.")
                .range(anchor)
                .withFix(new AddSelfParameterFix(funcDef, ownerShort, paramName))
                .create();
        }

        FullyQualifiedName ownerFqn = InterfaceService.resolveOwnerType(ownerText, ModuleName.from(funcDef));
        for (FullyQualifiedName iface : InterfaceService.INSTANCE.getImplementedInterfaces(ownerFqn, project))
        {
            for (C3FuncDef interfaceMethod : InterfaceService.INSTANCE.getInterfaceMethods(iface, project))
            {
                if (!implName.equals(interfaceMethod.getNameIdent())) continue;
                if (InterfaceService.hasAttribute(funcDef, "dynamic")) return;
                PsiElement anchor = funcDef.getNameIdentifier() != null ? funcDef.getNameIdentifier() : funcDef;
                holder.newAnnotation(
                        HighlightSeverity.ERROR,
                        "Method '" + ownerText + "." + implName + "' implements interface method '"
                            + iface.getFullName() + "." + implName + "' and must be marked '@dynamic'.")
                    .range(anchor)
                    .withFix(new AddDynamicAttributeFix(funcDef))
                    .create();
                return;
            }
        }
    }

    private static @NotNull String freeSelfParameterName(@NotNull C3FuncDef funcDef)
    {
        List<String> taken = InterfaceService.collectParameterNames(funcDef);
        if (!taken.contains("self")) return "self";
        if (!taken.contains("this")) return "this";
        return "self_";
    }

    private void annotateMacroDefinition(@NotNull C3MacroDefinition macroDef, @NotNull AnnotationHolder holder)
    {
        String name = macroDef.getName();
        if (name == null || name.isEmpty() || name.charAt(0) == '@') return;
        C3MacroParams params = macroDef.getMacroParams();
        if (params == null || params.getParameterList() == null) return;
        for (C3ParamDecl decl : params.getParameterList().getParamDeclList())
        {
            C3Parameter parameter = decl.getParameter();
            if (parameter == null) continue;
            String text = parameter.getText();
            if (text == null) continue;
            String clean = text.strip();
            if (clean.startsWith("&") || clean.startsWith("#"))
            {
                PsiElement anchor = macroDef.getNameIdentifier() != null ? macroDef.getNameIdentifier() : macroDef;
                holder.newAnnotation(
                        HighlightSeverity.ERROR,
                        "Macro '" + name + "' uses reference/expression parameters and must have a name starting with '@'.")
                    .range(anchor)
                    .create();
                return;
            }
        }
    }

    private void annotateCast(@NotNull C3UnaryExpr unary, @NotNull AnnotationHolder holder)
    {
        C3UnaryOp op = unary.getUnaryOp();
        C3Type castType = op.getType();
        if (castType == null) return;
        if (DumbService.isDumb(unary.getProject())) return;
        C3Expr operand = unary.getExpr();
        if (operand == null) return;
        InferredType source = TypeChecker.infer(operand);
        TypeChecker.CastDiagnostic diagnostic = TypeChecker.checkCast(
            unary.getProject(), ModuleName.from(unary), castType.getText(), source, operand);
        if (diagnostic == null) return;
        HighlightSeverity severity = diagnostic.warning ? HighlightSeverity.WEAK_WARNING : HighlightSeverity.ERROR;
        holder.newAnnotation(severity, diagnostic.message).range(op).create();
    }

    private void annotateInterfaceImpl(@NotNull C3InterfaceImpl impl, @NotNull AnnotationHolder holder)
    {
        Project project = impl.getProject();
        if (DumbService.isDumb(project)) return;
        ModuleName contextModule = ModuleName.from(impl);

        C3TypeName firstName = impl.getTypeName();
        PsiElement firstAnchor = firstName.getNameIdentifier() != null ? firstName.getNameIdentifier() : firstName;
        checkContractReference(firstAnchor, firstName.getText(), contextModule, project, holder);

        for (C3Type contractType : impl.getTypeList())
        {
            PsiElement anchor = contractType;
            if (contractType.getBaseType() != null && contractType.getBaseType().getNameIdentElement() != null)
            {
                anchor = contractType.getBaseType().getNameIdentElement();
            }
            checkContractReference(anchor, contractType.getText(), contextModule, project, holder);
        }
    }

    private void checkContractReference(
            @NotNull PsiElement anchor,
            @NotNull String contractText,
            @Nullable ModuleName contextModule,
            @NotNull Project project,
            @NotNull AnnotationHolder holder)
    {
        FullyQualifiedName iface = InterfaceService.parseContractReference(contractText, contextModule);
        if (iface == null || iface.getName().isEmpty()) return;
        if (!InterfaceService.INSTANCE.findInterfaceDefinitions(iface, project).isEmpty()) return;
        if (!InterfaceService.INSTANCE.findTypeDeclarations(iface, project).isEmpty())
        {
            holder.newAnnotation(HighlightSeverity.ERROR, "'" + contractText.strip() + "' is not an interface.")
                .range(anchor)
                .create();
            return;
        }
        holder.newAnnotation(HighlightSeverity.ERROR, "Unresolved interface '" + contractText.strip() + "'.")
            .range(anchor)
            .create();
    }

    private void annotateLocalDeclaration(@NotNull C3LocalDeclarationStmt decl, @NotNull AnnotationHolder holder)
    {
        if (DumbService.isDumb(decl.getProject())) return;
        C3DeclStmtAfterType after = decl.getDeclStmtAfterType();
        if (after == null) return;
        String targetText = decl.getOptionalType().getType().getText();
        if (targetText.isBlank()) return;
        boolean nullableTarget = decl.getOptionalType().getNode().findChildByType(C3Types.QUESTION) != null;
        for (C3LocalDeclAfterType declarator : after.getLocalDeclAfterTypeList())
        {
            if (declarator.getNameIdent() != null && declarator.getNameIdent().startsWith("$")) continue;
            C3Expr init = declarator.getExpr();
            if (init == null) continue;
            InferredType source = TypeChecker.infer(init);
            if (nullableTarget && source != null && source.getKind() == InferredType.Kind.NULL) continue;
            String error = TypeChecker.assignmentError(decl.getProject(), ModuleName.from(decl), targetText, source);
            if (error != null) holder.newAnnotation(HighlightSeverity.ERROR, error).range(init).create();
        }
    }

    private void annotateAssignment(@NotNull C3BinaryExpr binary, @NotNull AnnotationHolder holder)
    {
        if (TypeChecker.assignmentOperator(binary) == null) return;
        if (DumbService.isDumb(binary.getProject())) return;
        C3Expr rhs = binary.getRight();
        if (rhs == null) return;
        String lhsType = assignmentTargetType(binary.getLeft());
        if (lhsType == null) return;
        String error = TypeChecker.assignmentError(binary.getProject(), ModuleName.from(binary), lhsType, TypeChecker.infer(rhs));
        if (error != null) holder.newAnnotation(HighlightSeverity.ERROR, error).range(rhs).create();
    }

    private static @Nullable String assignmentTargetType(@NotNull C3Expr lhs)
    {
        if (lhs instanceof C3PathIdentExpr pathIdentExpr)
        {
            C3PathIdent pathIdent = pathIdentExpr.getPathIdent();
            if (pathIdent.getPath() != null) return null;
            PsiElement resolved;
            try
            {
                resolved = pathIdent.getReference().resolve();
            }
            catch (Exception e)
            {
                return null;
            }
            if (resolved == null) return null;
            return TypeChecker.declaredTypeText(resolved);
        }
        if (lhs instanceof C3CallExpr call
            && call.getCallExprTail() != null
            && call.getCallExprTail().getCallInvocation() == null
            && call.getCallExprTail().getAccessIdent() != null)
        {
            PsiElement resolved;
            try
            {
                resolved = call.getCallExprTail().getAccessIdent().getReference().resolve();
            }
            catch (Exception e)
            {
                return null;
            }
            if (resolved instanceof C3StructMemberDeclaration member && member.getStructPathType() != null)
            {
                return member.getStructPathType().getFullName();
            }
        }
        return null;
    }

    private void annotateReturnStmt(@NotNull C3ReturnStmt ret, @NotNull AnnotationHolder holder)
    {
        if (DumbService.isDumb(ret.getProject())) return;
        C3FuncDef funcDef = TypeChecker.enclosingFunction(ret);
        if (funcDef == null) return;
        ShortType returnType = funcDef.getReturnType();
        String returnText = returnType != null && returnType.getValue() != null ? returnType.getValue() : "void";
        C3Expr expr = ret.getExpr();
        if (expr == null)
        {
            if (!TypeChecker.isVoidType(returnText))
            {
                holder.newAnnotation(
                        HighlightSeverity.ERROR,
                        "Expected to return a value of type '" + TypeChecker.shortName(returnText) + "'.")
                    .range(returnKeywordOrSelf(ret))
                    .create();
            }
            return;
        }
        if (TypeChecker.isVoidType(returnText))
        {
            holder.newAnnotation(HighlightSeverity.ERROR, "Cannot return a value from a void function.")
                .range(expr)
                .create();
            return;
        }
        String error = TypeChecker.returnError(ret.getProject(), ModuleName.from(ret), returnText, TypeChecker.infer(expr));
        if (error != null) holder.newAnnotation(HighlightSeverity.ERROR, error).range(expr).create();
    }

    private static @NotNull PsiElement returnKeywordOrSelf(@NotNull C3ReturnStmt ret)
    {
        ASTNode keyword = ret.getNode().findChildByType(C3Types.KW_RETURN);
        return keyword != null ? keyword.getPsi() : ret;
    }

    private void annotateReturnCoverage(@NotNull C3FuncDef funcDef, @NotNull AnnotationHolder holder)
    {
        if (DumbService.isDumb(funcDef.getProject())) return;
        ShortType returnType = funcDef.getReturnType();
        if (returnType == null || returnType.getValue() == null || TypeChecker.isVoidType(returnType.getValue())) return;
        PsiElement parent = funcDef.getParent();
        if (!(parent instanceof C3FuncDefinition definition)
            || definition.getMacroFuncBody() == null
            || definition.getMacroFuncBody().getCompoundStatement() == null) return;
        C3CompoundStatement body = definition.getMacroFuncBody().getCompoundStatement();
        // Loops may never fall off the end: skip instead of false-positive.
        if (!PsiTreeUtil.collectElementsOfType(body, C3ForStmt.class).isEmpty()
            || !PsiTreeUtil.collectElementsOfType(body, C3WhileStmt.class).isEmpty()
            || !PsiTreeUtil.collectElementsOfType(body, C3ForeachStmt.class).isEmpty()
            || !PsiTreeUtil.collectElementsOfType(body, C3DoStmt.class).isEmpty()) return;
        for (C3ReturnStmt ret : PsiTreeUtil.collectElementsOfType(body, C3ReturnStmt.class))
        {
            if (funcDef.equals(TypeChecker.enclosingFunction(ret))) return;
        }
        PsiElement anchor = funcDef.getNameIdentifier() != null ? funcDef.getNameIdentifier() : funcDef;
        holder.newAnnotation(
                HighlightSeverity.ERROR,
                "Missing return of type '" + TypeChecker.shortName(returnType.getValue())
                    + "' in function '" + funcDef.getFqName().getName() + "'.")
            .range(anchor)
            .create();
    }

    private void annotateModulePath(@NotNull PsiElement element, @NotNull AnnotationHolder annotationHolder)
    {
        for (ASTNode node : element.getNode().getChildren(null))
        {
            if (node.getElementType() == C3Types.IDENT)
            {
                annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                    .textAttributes(C3SyntaxHighlighter.MODULE_KEY).range(node.getTextRange()).create();
            }
        }
    }

    private void annotatePath(@NotNull C3Path path, @NotNull AnnotationHolder annotationHolder)
    {
        for (ASTNode node : path.getNode().getChildren(null))
        {
            if (node.getElementType() == C3Types.IDENT)
            {
                annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                    .textAttributes(C3SyntaxHighlighter.MODULE_KEY).range(node.getTextRange()).create();
            }
        }
    }

    private void annotateParameter(@NotNull C3Parameter param, @NotNull AnnotationHolder annotationHolder)
    {
        for (ASTNode node : param.getNode().getChildren(null))
        {
            if (node.getElementType() == C3Types.IDENT)
            {
                annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                    .textAttributes(C3SyntaxHighlighter.PARAMETER_KEY).range(node.getTextRange()).create();
            }
        }
    }

    private void annotateAccessIdent(@NotNull C3AccessIdent accessIdent, @NotNull AnnotationHolder annotationHolder)
    {
        PsiElement nameElement = accessIdent.getNameIdentElement();
        if (nameElement == null) nameElement = accessIdent;

        boolean isCall = false;
        PsiElement parent = accessIdent.getParent();
        if (parent != null && parent.getParent() instanceof C3CallExpr accessCall)
        {
            PsiElement grandParent = accessCall.getParent();
            if (grandParent instanceof C3CallExpr callExpr
                && callExpr.getExpr() == accessCall
                && callExpr.getCallExprTail() != null
                && callExpr.getCallExprTail().getCallInvocation() != null)
            {
                isCall = true;
            }
        }

        TextAttributesKey key = isCall ? C3SyntaxHighlighter.METHOD_CALL_KEY : C3SyntaxHighlighter.FIELD_KEY;
        annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
            .textAttributes(key).range(nameElement).create();
    }

    private void annotatePathIdent(@NotNull C3PathIdent pathIdent, @NotNull AnnotationHolder annotationHolder)
    {
        PsiElement nameElement = pathIdent.getNameIdentElement();
        if (nameElement == null) return;
        String name = nameElement.getText();
        if (name == null || name.isEmpty()) return;

        boolean isCall = false;
        PsiElement parent = pathIdent.getParent();
        if (parent != null && parent.getParent() instanceof C3CallExpr callExpr)
        {
            if (callExpr.getExpr() == parent
                && callExpr.getCallExprTail() != null
                && callExpr.getCallExprTail().getCallInvocation() != null)
            {
                isCall = true;
            }
        }

        if (isCall)
        {
            annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                .textAttributes(C3SyntaxHighlighter.FUNCTION_CALL_KEY).range(nameElement).create();
            return;
        }

        if (pathIdent.getPath() != null)
        {
            return;
        }

        if ("this".equals(name) || "self".equals(name))
        {
            annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                .textAttributes(C3SyntaxHighlighter.PARAMETER_KEY).range(nameElement).create();
            return;
        }

        PsiElement current = pathIdent;
        while (current != null && !(current instanceof C3File))
        {
            if (current instanceof C3ForeachStmt foreachStmt)
            {
                C3ForeachVars vars = foreachStmt.getForeachVars();
                if (vars != null)
                {
                    for (C3ForeachVar v : vars.getForeachVarList())
                    {
                        ASTNode idNode = v.getNode().findChildByType(C3Types.IDENT);
                        if (idNode != null && name.equals(idNode.getText()))
                        {
                            annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                                .textAttributes(C3SyntaxHighlighter.LOCAL_VARIABLE_KEY).range(nameElement).create();
                            return;
                        }
                    }
                }
            }
            else if (current instanceof C3CompoundStatement compoundStatement)
            {
                for (C3StatementList statementList : compoundStatement.getStatementListList())
                {
                    for (C3Statement stmt : statementList.getStatementList())
                    {
                        if (stmt.getTextOffset() >= pathIdent.getTextOffset()) break;
                        if (stmt.getLocalDeclarationStmt() != null)
                        {
                            C3DeclStmtAfterType afterType = stmt.getLocalDeclarationStmt().getDeclStmtAfterType();
                            if (afterType != null)
                            {
                                for (C3LocalDeclAfterType decl : afterType.getLocalDeclAfterTypeList())
                                {
                                    if (name.equals(decl.getNameIdent()))
                                    {
                                        annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                                            .textAttributes(C3SyntaxHighlighter.LOCAL_VARIABLE_KEY).range(nameElement).create();
                                        return;
                                    }
                                }
                            }
                        }
                        else if (stmt.getVarStmt() != null && stmt.getVarStmt().getVarDecl() != null)
                        {
                            ASTNode idNode = stmt.getVarStmt().getVarDecl().getNode().findChildByType(C3Types.IDENT);
                            if (idNode != null && name.equals(idNode.getText()))
                            {
                                annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                                    .textAttributes(C3SyntaxHighlighter.LOCAL_VARIABLE_KEY).range(nameElement).create();
                                return;
                            }
                        }
                    }
                }
            }
            else if (current instanceof C3FuncDef funcDef)
            {
                C3ParameterList paramList = funcDef.getFnParameterList().getParameterList();
                if (paramList != null)
                {
                    for (C3ParamDecl paramDecl : paramList.getParamDeclList())
                    {
                        C3Parameter p = paramDecl.getParameter();
                        for (ASTNode node : p.getNode().getChildren(null))
                        {
                            if (node.getElementType() == C3Types.IDENT && name.equals(node.getText()))
                            {
                                annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                                    .textAttributes(C3SyntaxHighlighter.PARAMETER_KEY).range(nameElement).create();
                                return;
                            }
                        }
                    }
                }
            }
            else if (current instanceof C3MacroDefinition macroDef)
            {
                C3MacroParams params = macroDef.getMacroParams();
                C3ParameterList paramList = params != null ? params.getParameterList() : null;
                if (paramList != null)
                {
                    for (C3ParamDecl paramDecl : paramList.getParamDeclList())
                    {
                        C3Parameter p = paramDecl.getParameter();
                        for (ASTNode node : p.getNode().getChildren(null))
                        {
                            if (node.getElementType() == C3Types.IDENT && name.equals(node.getText()))
                            {
                                annotationHolder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                                    .textAttributes(C3SyntaxHighlighter.PARAMETER_KEY).range(nameElement).create();
                                return;
                            }
                        }
                    }
                }
            }
            current = current.getParent();
        }
    }
}
