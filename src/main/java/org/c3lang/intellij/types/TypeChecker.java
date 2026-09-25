package org.c3lang.intellij.types;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.stubs.StubIndex;
import com.intellij.psi.util.PsiTreeUtil;
import org.c3lang.intellij.project.C3ProjectService;
import org.c3lang.intellij.index.TypeIndex;
import org.c3lang.intellij.psi.C3AccessIdent;
import org.c3lang.intellij.psi.C3AliasTypeDecl;
import org.c3lang.intellij.psi.C3Arg;
import org.c3lang.intellij.psi.C3ArgList;
import org.c3lang.intellij.psi.C3BaseType;
import org.c3lang.intellij.psi.C3BinaryExpr;
import org.c3lang.intellij.psi.C3BinaryOp;
import org.c3lang.intellij.psi.C3BitstructDeclaration;
import org.c3lang.intellij.psi.C3CallExpr;
import org.c3lang.intellij.psi.C3CallablePsiElement;
import org.c3lang.intellij.psi.C3CompoundInitExpr;
import org.c3lang.intellij.psi.C3ConstDeclarationStmt;
import org.c3lang.intellij.psi.C3EnumAccessExpr;
import org.c3lang.intellij.psi.C3EnumConstant;
import org.c3lang.intellij.psi.C3EnumDeclaration;
import org.c3lang.intellij.psi.C3Expr;
import org.c3lang.intellij.psi.C3FuncDef;
import org.c3lang.intellij.psi.C3FuncDefinition;
import org.c3lang.intellij.psi.C3GroupedExpr;
import org.c3lang.intellij.psi.C3InitListExpr;
import org.c3lang.intellij.psi.C3InitializerList;
import org.c3lang.intellij.psi.C3InterfaceDefinition;
import org.c3lang.intellij.psi.C3KeywordExpr;
import org.c3lang.intellij.psi.C3LambdaDeclExpr;
import org.c3lang.intellij.psi.C3LambdaDeclShortExpr;
import org.c3lang.intellij.psi.C3LiteralExpr;
import org.c3lang.intellij.psi.C3LocalDeclAfterType;
import org.c3lang.intellij.psi.C3LocalDeclarationStmt;
import org.c3lang.intellij.psi.C3MacroDefinition;
import org.c3lang.intellij.psi.C3ReturnStmt;
import org.c3lang.intellij.psi.C3ParamDecl;
import org.c3lang.intellij.psi.C3Parameter;
import org.c3lang.intellij.psi.C3PathAtIdentExpr;
import org.c3lang.intellij.psi.C3PathConstExpr;
import org.c3lang.intellij.psi.C3PathIdent;
import org.c3lang.intellij.psi.C3PathIdentExpr;
import org.c3lang.intellij.psi.C3PsiElement;
import org.c3lang.intellij.psi.C3StringExpr;
import org.c3lang.intellij.psi.C3StructDeclaration;
import org.c3lang.intellij.psi.C3StructMemberDeclaration;
import org.c3lang.intellij.psi.C3TernaryExpr;
import org.c3lang.intellij.psi.C3Type;
import org.c3lang.intellij.psi.C3TypeName;
import org.c3lang.intellij.psi.C3TypedefDecl;
import org.c3lang.intellij.psi.C3TypedefType;
import org.c3lang.intellij.psi.C3Types;
import org.c3lang.intellij.psi.C3UnaryExpr;
import org.c3lang.intellij.psi.FullyQualifiedName;
import org.c3lang.intellij.psi.ModuleName;
import org.c3lang.intellij.psi.ShortType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Expression type inference and assignability checks for C3.
 *
 * <p>Rules follow {@code docs/basic-types-and-values.md}: integer literal fits,
 * implicit literal-to-float conversion, string literal targets, {@code void*}
 * converting to any pointer. Anything that cannot be proven is
 * {@code null} (unknown) and never produces an error.
 */
public final class TypeChecker
{
    private static final int MAX_DEPTH = 8;

    /**
     * Integer type name to [bits, signed(1/0)]. Pointer-sized types assume a 64-bit target.
     */
    private static final Map<String, int[]> INT_TYPES = new HashMap<>();

    private static final Map<String, Integer> FLOAT_TYPES = new HashMap<>();

    static
    {
        INT_TYPES.put("ichar", new int[]{8, 1});
        INT_TYPES.put("char", new int[]{8, 0});
        INT_TYPES.put("short", new int[]{16, 1});
        INT_TYPES.put("ushort", new int[]{16, 0});
        INT_TYPES.put("int", new int[]{32, 1});
        INT_TYPES.put("uint", new int[]{32, 0});
        INT_TYPES.put("long", new int[]{64, 1});
        INT_TYPES.put("ulong", new int[]{64, 0});
        INT_TYPES.put("int128", new int[]{128, 1});
        INT_TYPES.put("uint128", new int[]{128, 0});
        INT_TYPES.put("iptr", new int[]{64, 1});
        INT_TYPES.put("uptr", new int[]{64, 0});
        INT_TYPES.put("sz", new int[]{64, 1});
        INT_TYPES.put("usz", new int[]{64, 0});

        FLOAT_TYPES.put("float16", 16);
        FLOAT_TYPES.put("bfloat16", 16);
        FLOAT_TYPES.put("float", 32);
        FLOAT_TYPES.put("double", 64);
        FLOAT_TYPES.put("float128", 128);
    }

    private TypeChecker()
    {
    }

    // ------------------------------------------------------------------
    // Entry points used by the annotator
    // ------------------------------------------------------------------

    /**
     * @return error message or {@code null} when the value fits the target.
     */
    public static @Nullable String assignmentError(
            @NotNull Project project,
            @Nullable ModuleName contextModule,
            @NotNull String targetText,
            @Nullable InferredType source)
    {
        if (source == null) return null;
        Mismatch mismatch = check(project, contextModule, targetText, source);
        if (mismatch == null) return null;
        if (mismatch.intValue != null)
        {
            return "Integer value " + mismatch.intValue + " does not fit in type '" + mismatch.targetName + "'.";
        }
        if (mismatch.floatValue != null)
        {
            return "Floating point value " + mismatch.floatValue + " does not fit in type '" + mismatch.targetName + "'.";
        }
        if (mismatch.count >= 0)
        {
            return "Expected " + mismatch.count + " elements for type '" + mismatch.targetName
                + "' but got " + mismatch.actual + ".";
        }
        return "Cannot assign '" + mismatch.sourceName + "' to '" + mismatch.targetName + "'.";
    }

    /**
     * @return error message or {@code null} when the value fits the return type.
     */
    public static @Nullable String returnError(
            @NotNull Project project,
            @Nullable ModuleName contextModule,
            @NotNull String returnTypeText,
            @Nullable InferredType source)
    {
        if (source == null) return null;
        Mismatch mismatch = check(project, contextModule, returnTypeText, source);
        if (mismatch == null) return null;
        if (mismatch.intValue != null)
        {
            return "Integer value " + mismatch.intValue + " does not fit in type '" + mismatch.targetName + "'.";
        }
        if (mismatch.floatValue != null)
        {
            return "Floating point value " + mismatch.floatValue + " does not fit in type '" + mismatch.targetName + "'.";
        }
        if (mismatch.count >= 0)
        {
            return "Expected " + mismatch.count + " elements for type '" + mismatch.targetName
                + "' but got " + mismatch.actual + ".";
        }
        return "Cannot return '" + mismatch.sourceName + "' from function returning '" + mismatch.targetName + "'.";
    }

    // ------------------------------------------------------------------
    // Compatibility
    // ------------------------------------------------------------------

    /**
     * @return error message or {@code null} when the argument fits the parameter.
     */
    public static @Nullable String argumentError(
            @NotNull Project project,
            @Nullable ModuleName contextModule,
            @NotNull String paramName,
            @NotNull String paramTypeText,
            @Nullable InferredType arg)
    {
        if (arg == null) return null;
        Mismatch mismatch = check(project, contextModule, paramTypeText, arg);
        if (mismatch == null) return null;
        // Undeclared (e.g. generic) parameter types are not checked.
        if (!isDeclaredType(paramTypeText, project, contextModule)) return null;
        if (mismatch.intValue != null)
        {
            return "Integer value " + mismatch.intValue + " does not fit in type '" + mismatch.targetName + "'.";
        }
        if (mismatch.floatValue != null)
        {
            return "Floating point value " + mismatch.floatValue + " does not fit in type '" + mismatch.targetName + "'.";
        }
        if (mismatch.count >= 0)
        {
            return "Expected " + mismatch.count + " elements for type '" + mismatch.targetName
                + "' but got " + mismatch.actual + ".";
        }
        return "Cannot pass '" + mismatch.sourceName + "' for parameter '" + paramName
            + "' of type '" + mismatch.targetName + "'.";
    }

    /**
     * Whether a written type is a known type: a primitive/keyword, a compound
     * type, or a bare identifier declared as a type somewhere.
     */
    static boolean isDeclaredType(
            @NotNull String typeText,
            @NotNull Project project,
            @Nullable ModuleName contextModule)
    {
        String clean = normalize(typeText);
        while (true)
        {
            if (clean.endsWith("*") || clean.endsWith("?") || clean.endsWith("!"))
            {
                clean = clean.substring(0, clean.length() - 1);
                continue;
            }
            VectorInfo vector = parseVector(clean);
            if (vector != null)
            {
                clean = vector.element;
                continue;
            }
            VectorInfo array = parseArray(clean);
            if (array != null)
            {
                clean = array.element;
                continue;
            }
            break;
        }
        String shortName = shortName(clean);
        if (INT_TYPES.containsKey(shortName) || FLOAT_TYPES.containsKey(shortName)) return true;
        switch (shortName)
        {
            case "void", "bool", "char", "String", "ZString", "any", "typeid", "fault" -> { return true; }
            default -> {}
        }
        if (!shortName.matches("[A-Za-z_][A-Za-z_0-9]*")) return true;
        if (DumbService.isDumb(project)) return true;
        for (String key : StubIndex.getInstance().getAllKeys(TypeIndex.KEY, project))
        {
            if (!key.equals(shortName) && !key.endsWith("::" + shortName)) continue;
            for (C3PsiElement element : StubIndex.getElements(
                    TypeIndex.KEY,
                    key,
                    project,
                    C3ProjectService.getInstance(project).getSearchScope(),
                    C3PsiElement.class))
            {
                if (!(element instanceof C3TypeName typeName)) continue;
                if (!typeName.getText().strip().equals(shortName)) continue;
                PsiElement parent = typeName.getParent();
                if (parent instanceof C3StructDeclaration
                    || parent instanceof C3EnumDeclaration
                    || parent instanceof C3InterfaceDefinition
                    || parent instanceof C3TypedefDecl
                    || parent instanceof C3BitstructDeclaration
                    || parent instanceof C3AliasTypeDecl)
                {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isVoidType(@NotNull String typeText)
    {
        return normalize(typeText).equals("void");
    }

    public static @NotNull String normalize(@NotNull String typeText)
    {
        return typeText.replaceAll("\\s+", "");
    }

    public static @NotNull String shortName(@NotNull String typeText)
    {
        String clean = normalize(typeText);
        int separator = clean.lastIndexOf("::");
        return separator >= 0 ? clean.substring(separator + 2) : clean;
    }

    private static boolean namesEqual(@NotNull String a, @NotNull String b)
    {
        if (a.equals(b)) return true;
        String shortA = shortName(a);
        String shortB = shortName(b);
        return !shortA.isEmpty() && shortA.equals(shortB);
    }

    /**
     * @return {@code null} when assignable, otherwise the mismatch details.
     */
    static @Nullable Mismatch check(
            @NotNull Project project,
            @Nullable ModuleName contextModule,
            @NotNull String targetText,
            @NotNull InferredType source)
    {
        String target = normalize(targetText);
        if (target.equals("_") || target.equals("auto")) return null;

        if (source.getKind() == InferredType.Kind.INIT_LIST)
        {
            String resolvedTarget = resolveAlias(target, project, contextModule, 0);
            return checkInitList(project, contextModule, resolvedTarget != null ? resolvedTarget : target, source);
        }

        Mismatch direct = checkOnce(target, source);
        if (direct == null) return null;

        // Resolve type aliases (and typedefs for literals / inline typedef sources).
        TargetInfo resolvedTarget = resolveTargetType(project, contextModule, target);
        InferredType resolvedSource = source;
        String resolvedSourceName = resolveSourceType(project, contextModule, source.getName());
        if (resolvedSourceName != null) resolvedSource = kindOf(resolvedSourceName);

        if (resolvedTarget == null && resolvedSource == source) return direct;
        String finalTarget = resolvedTarget != null ? resolvedTarget.text : target;
        if (resolvedTarget != null && resolvedTarget.typedefOnly && !source.isLiteral()) return direct;
        Mismatch second = checkOnce(finalTarget, resolvedSource);
        if (second == null) return null;
        // Report with underlying type names when resolution helped describe the problem.
        if (resolvedTarget != null || resolvedSource != source)
        {
            return second;
        }
        return direct;
    }

    /**
     * Pure assignability check without alias resolution.
     */
    static @Nullable Mismatch checkOnce(@NotNull String targetText, @NotNull InferredType source)
    {
        String target = normalize(targetText);
        String base = stripOptional(target);
        String targetName = shortName(base);
        if (namesEqual(base, source.getName())) return null;
        // `any` accepts any value (but void is not a value).
        if (base.equals("any") && source.getKind() != InferredType.Kind.VOID) return null;

        if (source.getKind() == InferredType.Kind.INIT_LIST)
        {
            // Unreachable via check(); deny in the pure path.
            return new Mismatch(source.getName(), targetName, null, null, -1, -1);
        }

        VectorInfo targetVector = parseVector(base);
        if (targetVector != null)
        {
            if (isVectorName(source.getName()))
            {
                return namesEqual(base, source.getName())
                    ? null
                    : new Mismatch(source.getName(), targetName, null, null, -1, -1);
            }
            // A scalar widens elementwise into the vector.
            Mismatch element = checkOnce(targetVector.element, source);
            if (element == null) return null;
            return new Mismatch(element.sourceName, targetName, element.intValue, element.floatValue, -1, -1);
        }

        switch (source.getKind())
        {
            case VOID:
            case INIT_LIST:
                break;
            case NULL:
                if (target.endsWith("*") || target.endsWith("?") || target.endsWith("!")) return null;
                break;
            case BOOL:
                break;
            case STRING:
                if (isStringTarget(base, source)) return null;
                break;
            case CHAR:
            case INT:
                if (FLOAT_TYPES.containsKey(base)) return null;
                if (intAssignable(base, source)) return null;
                if (source.isLiteral() && source.getIntValue() != null && INT_TYPES.get(base) != null)
                {
                    return new Mismatch(source.getName(), targetName, source.getIntValue(), null, -1, -1);
                }
                break;
            case FLOAT:
                if (floatAssignable(base, source)) return null;
                if (source.isLiteral() && source.getFloatValue() != null && FLOAT_TYPES.get(base) != null)
                {
                    return new Mismatch(source.getName(), targetName, null, source.getFloatValue(), -1, -1);
                }
                break;
            case POINTER:
                if (source.getName().equals("void*") || base.equals("void*")) return null;
                break;
            case NAMED:
                if (base.equals("any") || source.getName().equals("any")) return null;
                break;
        }
        return new Mismatch(source.getName(), targetName, null, null, -1, -1);
    }

    private static final class Mismatch
    {
        final @NotNull String sourceName;
        final @NotNull String targetName;
        final @Nullable BigInteger intValue;
        final @Nullable Double floatValue;
        final long count;
        final long actual;

        Mismatch(@NotNull String sourceName, @NotNull String targetName, @Nullable BigInteger intValue, @Nullable Double floatValue, long count, long actual)
        {
            this.sourceName = sourceName;
            this.targetName = targetName;
            this.intValue = intValue;
            this.floatValue = floatValue;
            this.count = count;
            this.actual = actual;
        }
    }

    // ------------------------------------------------------------------
    // Aliases and typedefs
    // ------------------------------------------------------------------

    private static final class TargetInfo
    {
        final @NotNull String text;
        final boolean typedefOnly;

        TargetInfo(@NotNull String text, boolean typedefOnly)
        {
            this.text = text;
            this.typedefOnly = typedefOnly;
        }
    }

    private static final class NamedTypeDecl
    {
        final @NotNull C3TypeName nameElement;
        final @NotNull String underlying;
        final boolean isTypedef;
        final boolean inlineTypedef;
        final @Nullable ModuleName module;

        NamedTypeDecl(
                @NotNull C3TypeName nameElement,
                @NotNull String underlying,
                boolean isTypedef,
                boolean inlineTypedef,
                @Nullable ModuleName module)
        {
            this.nameElement = nameElement;
            this.underlying = underlying;
            this.isTypedef = isTypedef;
            this.inlineTypedef = inlineTypedef;
            this.module = module;
        }
    }

    private static @Nullable TargetInfo resolveTargetType(
            @NotNull Project project,
            @Nullable ModuleName contextModule,
            @NotNull String target)
    {
        String underlying = resolveAlias(target, project, contextModule, 0);
        if (underlying != null) return new TargetInfo(underlying, false);
        String typedefTarget = resolveTypedef(target, project, contextModule, 0);
        if (typedefTarget != null) return new TargetInfo(typedefTarget, true);
        return null;
    }

    private static @Nullable String resolveSourceType(
            @NotNull Project project,
            @Nullable ModuleName contextModule,
            @NotNull String sourceName)
    {
        String underlying = resolveAlias(sourceName, project, contextModule, 0);
        if (underlying != null) return underlying;
        return resolveInlineTypedef(sourceName, project, contextModule, 0);
    }

    /**
     * Underlying text of a type alias like {@code alias CharPtr = char*;}, or {@code null}.
     */
    static @Nullable String resolveAlias(
            @NotNull String typeName,
            @NotNull Project project,
            @Nullable ModuleName contextModule,
            int depth)
    {
        if (depth > 4 || DumbService.isDumb(project)) return null;
        if (!isUserTypeName(typeName)) return null;
        String simpleName = shortName(normalize(typeName));
        NamedTypeDecl match = pickDeclaration(findNamedTypeDecls(simpleName, project), typeName, contextModule);
        if (match == null || match.isTypedef) return null;
        String chained = resolveAlias(match.underlying, project, contextModule, depth + 1);
        return chained != null ? chained : match.underlying;
    }

    /**
     * Underlying text of a plain (non-inline) typedef, used for literals only.
     */
    private static @Nullable String resolveTypedef(
            @NotNull String typeName,
            @NotNull Project project,
            @Nullable ModuleName contextModule,
            int depth)
    {
        if (depth > 4 || DumbService.isDumb(project)) return null;
        if (!isUserTypeName(typeName)) return null;
        NamedTypeDecl match = pickDeclaration(
            findNamedTypeDecls(shortName(normalize(typeName)), project), typeName, contextModule);
        if (match == null || !match.isTypedef || match.inlineTypedef) return null;
        String chained = resolveTypedef(match.underlying, project, contextModule, depth + 1);
        return chained != null ? chained : match.underlying;
    }

    /**
     * Underlying text of an {@code inline} typedef, convertible both ways.
     */
    private static @Nullable String resolveInlineTypedef(
            @NotNull String typeName,
            @NotNull Project project,
            @Nullable ModuleName contextModule,
            int depth)
    {
        if (depth > 4 || DumbService.isDumb(project)) return null;
        if (!isUserTypeName(typeName)) return null;
        NamedTypeDecl match = pickDeclaration(
            findNamedTypeDecls(shortName(normalize(typeName)), project), typeName, contextModule);
        if (match == null || !match.isTypedef || !match.inlineTypedef) return null;
        String chained = resolveInlineTypedef(match.underlying, project, contextModule, depth + 1);
        return chained != null ? chained : match.underlying;
    }

    private static boolean isUserTypeName(@NotNull String typeName)
    {
        String clean = normalize(typeName);
        if (clean.contains("{") || clean.contains("}") || clean.contains("[")
            || clean.contains("]") || clean.contains("*") || clean.contains("?")
            || clean.contains("!") || clean.contains("(") || clean.contains(" ")) return false;
        String simpleName = shortName(clean);
        if (INT_TYPES.containsKey(simpleName) || FLOAT_TYPES.containsKey(simpleName)) return false;
        return switch (simpleName)
        {
            case "void", "bool", "char", "String", "ZString", "any", "typeid", "fault" -> false;
            default -> simpleName.matches("[A-Za-z_][A-Za-z_0-9]*");
        };
    }

    private static @Nullable NamedTypeDecl pickDeclaration(
            @NotNull List<NamedTypeDecl> candidates,
            @NotNull String requestedText,
            @Nullable ModuleName contextModule)
    {
        if (candidates.isEmpty()) return null;
        String full = normalize(requestedText);
        for (NamedTypeDecl candidate : candidates)
        {
            if (candidate.module != null && full.equals(candidate.module.getValue() + "::" + candidate.nameElement.getText().strip()))
            {
                return candidate;
            }
        }
        if (contextModule != null)
        {
            for (NamedTypeDecl candidate : candidates)
            {
                if (contextModule.equals(candidate.module)) return candidate;
            }
        }
        return candidates.get(0);
    }

    private static @NotNull List<NamedTypeDecl> findNamedTypeDecls(@NotNull String shortName, @NotNull Project project)
    {
        List<NamedTypeDecl> result = new ArrayList<>();
        if (DumbService.isDumb(project)) return result;
        for (String key : StubIndex.getInstance().getAllKeys(TypeIndex.KEY, project))
        {
            if (!key.equals(shortName) && !key.endsWith("::" + shortName)) continue;
            for (C3PsiElement element : StubIndex.getElements(
                    TypeIndex.KEY,
                    key,
                    project,
                    C3ProjectService.getInstance(project).getSearchScope(),
                    C3PsiElement.class))
            {
                if (!(element instanceof C3TypeName typeName)) continue;
                PsiElement parent = typeName.getParent();
                boolean isTypedef = parent instanceof C3TypedefDecl;
                if (!(parent instanceof C3AliasTypeDecl) && !isTypedef) continue;
                if (!typeName.getText().strip().equals(shortName)) continue;
                String underlying = underlyingTypeText(parent);
                if (underlying == null) continue;
                boolean inline = isTypedef && hasInlineModifier(parent);
                ModuleName module = ModuleName.from(typeName);
                result.add(new NamedTypeDecl(typeName, underlying, isTypedef, inline, module));
                if (result.size() > 25) return result;
            }
        }
        return result;
    }

    private static @Nullable String underlyingTypeText(@NotNull PsiElement declaration)
    {
        C3TypedefType typedefType = null;
        if (declaration instanceof C3AliasTypeDecl aliasDecl)
        {
            if (aliasDecl.getGenericDecl() != null) return null;
            typedefType = aliasDecl.getTypedefType();
        }
        else if (declaration instanceof C3TypedefDecl typedefDecl)
        {
            if (typedefDecl.getGenericDecl() != null) return null;
            typedefType = typedefDecl.getTypedefType();
        }
        if (typedefType == null) return null;
        if (typedefType.getGenericParameters() != null) return null;
        C3Type type = typedefType.getType();
        if (type == null) return null;
        String text = type.getText();
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static boolean hasInlineModifier(@NotNull PsiElement declaration)
    {
        ASTNode inline = declaration.getNode().findChildByType(C3Types.KW_INLINE);
        return inline != null;
    }

    // ------------------------------------------------------------------
    // Vectors, arrays and initializer lists
    // ------------------------------------------------------------------

    private static final java.util.regex.Pattern VECTOR_PATTERN =
        java.util.regex.Pattern.compile("^(.+)\\[<(\\d+|\\*)>\\]$");
    private static final java.util.regex.Pattern ARRAY_PATTERN =
        java.util.regex.Pattern.compile("^(.+)\\[(\\d+|\\*|)\\]$");

    public static final class VectorInfo
    {
        public final @NotNull String element;
        public final long size;

        VectorInfo(@NotNull String element, long size)
        {
            this.element = element;
            this.size = size;
        }
    }

    public static @Nullable VectorInfo parseVector(@NotNull String typeText)
    {
        java.util.regex.Matcher matcher = VECTOR_PATTERN.matcher(normalize(typeText));
        if (!matcher.matches()) return null;
        String element = matcher.group(1);
        if (element.isEmpty()) return null;
        String size = matcher.group(2);
        return new VectorInfo(element, size.equals("*") ? -1 : Long.parseLong(size));
    }

    public static boolean isIntegerType(@NotNull String typeText)
    {
        return INT_TYPES.containsKey(shortName(normalize(typeText)));
    }

    public static boolean isFloatType(@NotNull String typeText)
    {
        return FLOAT_TYPES.containsKey(shortName(normalize(typeText)));
    }

    private static boolean isVectorName(@NotNull String typeText)
    {
        return VECTOR_PATTERN.matcher(normalize(typeText)).matches();
    }

    private static @Nullable VectorInfo parseArray(@NotNull String typeText)
    {
        java.util.regex.Matcher matcher = ARRAY_PATTERN.matcher(typeText);
        if (!matcher.matches()) return null;
        String element = matcher.group(1);
        if (element.isEmpty()) return null;
        String size = matcher.group(2);
        return new VectorInfo(element, size.isEmpty() || size.equals("*") ? -1 : Long.parseLong(size));
    }

    private static @Nullable Mismatch checkInitList(
            @NotNull Project project,
            @Nullable ModuleName contextModule,
            @NotNull String targetText,
            @NotNull InferredType source)
    {
        String base = stripOptional(normalize(targetText));
        String targetName = shortName(base);
        VectorInfo vector = parseVector(base);
        VectorInfo array = vector == null ? parseArray(base) : null;
        if (vector == null && array == null) return null;
        String element = vector != null ? vector.element : array.element;
        long expected = vector != null ? vector.size : array.size;

        List<InferredType> elements = source.getElements();
        if (!source.hasNamedArguments() && vector != null && expected >= 0 && elements.size() != expected)
        {
            return new Mismatch(source.getName(), targetName, null, null, expected, elements.size());
        }
        if (source.hasNamedArguments()) return null;
        for (InferredType elementType : elements)
        {
            if (elementType == null) continue;
            Mismatch elementMismatch = check(project, contextModule, element, elementType);
            if (elementMismatch != null)
            {
                return new Mismatch(
                    elementMismatch.sourceName,
                    targetName,
                    elementMismatch.intValue,
                    elementMismatch.floatValue,
                    -1, -1);
            }
        }
        return null;
    }

    private static @NotNull String stripOptional(@NotNull String target)
    {
        if ((target.endsWith("?") || target.endsWith("!")) && !target.endsWith("*")) return target.substring(0, target.length() - 1);
        return target;
    }

    private static boolean isStringTarget(@NotNull String base, @NotNull InferredType source)
    {
        if (source.isLiteral())
        {
            if (base.equals("String") || base.equals("ZString")
                || base.equals("char[]") || base.equals("ichar[]")
                || base.equals("char*") || base.equals("ichar*")) return true;
            return base.matches("(i)?char\\[(\\d*|\\*)\\]");
        }
        String name = source.getName();
        return (name.equals("String") && (base.equals("String") || base.equals("char[]")))
            || (name.equals("char[]") && (base.equals("String") || base.equals("char[]")))
            || (name.equals("ZString") && (base.equals("ZString") || base.equals("char*")))
            || (name.equals("char*") && (base.equals("ZString") || base.equals("char*")));
    }

    private static boolean intAssignable(@NotNull String base, @NotNull InferredType source)
    {
        int[] targetBits = INT_TYPES.get(base);
        if (targetBits == null) return false;
        if (source.isLiteral())
        {
            // Typed literal without a known value (e.g. a wide char literal):
            // only the exact type is accepted, checked by the caller.
            return source.getIntValue() != null && fits(source.getIntValue(), targetBits);
        }
        int[] sourceBits = INT_TYPES.get(source.getName());
        if (sourceBits == null) return false;
        return rangeFits(rangeMin(sourceBits), rangeMax(sourceBits), rangeMin(targetBits), rangeMax(targetBits));
    }

    private static boolean floatAssignable(@NotNull String base, @NotNull InferredType source)
    {
        Integer targetBits = FLOAT_TYPES.get(base);
        if (targetBits == null) return false;
        if (source.isLiteral())
        {
            Double value = source.getFloatValue();
            if (value == null) return true;
            if (value.isNaN() || value.isInfinite()) return false;
            double max = maxFloat(targetBits);
            return Math.abs(value) <= max;
        }
        Integer sourceBits = FLOAT_TYPES.get(source.getName());
        return sourceBits != null && sourceBits <= targetBits;
    }

    private static boolean fits(@NotNull BigInteger value, @NotNull int[] bits)
    {
        return rangeFits(value, value, rangeMin(bits), rangeMax(bits));
    }

    private static boolean rangeFits(@NotNull BigInteger sMin, @NotNull BigInteger sMax, @NotNull BigInteger tMin, @NotNull BigInteger tMax)
    {
        return sMin.compareTo(tMin) >= 0 && sMax.compareTo(tMax) <= 0;
    }

    private static @NotNull BigInteger rangeMin(@NotNull int[] bits)
    {
        if (bits[1] == 1) return BigInteger.ONE.shiftLeft(bits[0] - 1).negate();
        return BigInteger.ZERO;
    }

    private static @NotNull BigInteger rangeMax(@NotNull int[] bits)
    {
        if (bits[1] == 1) return BigInteger.ONE.shiftLeft(bits[0] - 1).subtract(BigInteger.ONE);
        return BigInteger.ONE.shiftLeft(bits[0]).subtract(BigInteger.ONE);
    }

    private static double maxFloat(int bits)
    {
        if (bits <= 16) return 65504.0;
        if (bits <= 32) return Float.MAX_VALUE;
        if (bits <= 64) return Double.MAX_VALUE;
        return Double.POSITIVE_INFINITY;
    }

    // ------------------------------------------------------------------
    // Declared type mapping
    // ------------------------------------------------------------------

    /**
     * Maps a written type text to an inferred type (never a literal).
     */
    public static @NotNull InferredType kindOf(@NotNull String typeText)
    {
        String text = normalize(typeText);
        if (text.equals("void")) return InferredType.voidType();
        if (text.equals("bool")) return InferredType.boolType(false);
        if (text.equals("char")) return InferredType.of(InferredType.Kind.CHAR, "char");
        if (INT_TYPES.containsKey(text)) return InferredType.of(InferredType.Kind.INT, text);
        if (FLOAT_TYPES.containsKey(text)) return InferredType.of(InferredType.Kind.FLOAT, text);
        if (text.equals("String") || text.equals("ZString")) return InferredType.of(InferredType.Kind.STRING, text);
        if (text.endsWith("*")) return InferredType.of(InferredType.Kind.POINTER, text);
        return InferredType.of(InferredType.Kind.NAMED, text);
    }

    // ------------------------------------------------------------------
    // Inference
    // ------------------------------------------------------------------

    public static @Nullable InferredType infer(@NotNull C3Expr expr)
    {
        return infer(expr, 0);
    }

    private static @Nullable InferredType infer(@NotNull C3Expr expr, int depth)
    {
        if (depth > MAX_DEPTH) return null;
        if (expr instanceof C3LiteralExpr) return inferLiteral(expr.getText());
        if (expr instanceof C3KeywordExpr) return inferKeyword(expr.getText());
        if (expr instanceof C3StringExpr) return inferString(expr.getText());
        if (expr instanceof C3GroupedExpr grouped) return grouped.getExpr() != null ? infer(grouped.getExpr(), depth + 1) : null;
        if (expr instanceof C3UnaryExpr unary) return inferUnary(unary, depth);
        if (expr instanceof C3BinaryExpr binary) return inferBinary(binary, depth);
        if (expr instanceof C3TernaryExpr ternary) return inferTernary(ternary, depth);
        if (expr instanceof C3PathIdentExpr pathIdent) return inferPathIdent(pathIdent, depth);
        if (expr instanceof C3PathConstExpr pathConst) return inferPathConst(pathConst, depth);
        if (expr instanceof C3CallExpr call) return inferCall(call, depth);
        if (expr instanceof C3EnumAccessExpr enumAccess) return inferEnumAccess(enumAccess);
        if (expr instanceof C3CompoundInitExpr compoundInit) return kindOf(compoundInit.getType().getText());
        if (expr instanceof C3InitListExpr initList) return inferInitList(initList, depth);
        return null;
    }

    private static @Nullable InferredType inferInitList(@NotNull C3InitListExpr initList, int depth)
    {
        C3InitializerList list = initList.getInitializerList();
        if (list == null || list.getArgList() == null) return InferredType.initList(List.of(), false);
        List<InferredType> elements = new ArrayList<>();
        boolean named = false;
        for (C3Arg arg : list.getArgList().getArgList())
        {
            if (arg.getNamedIdent() != null || arg.getParamPath() != null) named = true;
            C3Expr argExpr = arg.getExpr();
            elements.add(argExpr != null ? infer(argExpr, depth + 1) : null);
        }
        return InferredType.initList(elements, named);
    }

    private static @Nullable InferredType inferLiteral(@NotNull String text)
    {
        String clean = text.replace("_", "");
        if (clean.isEmpty()) return null;
        char first = clean.charAt(0);
        if (first == '.' || (Character.isDigit(first) && (clean.contains(".") || clean.contains("e") || clean.contains("E") || clean.contains("p") || clean.contains("P"))))
        {
            return parseFloatLiteral(clean);
        }
        if (Character.isDigit(first)) return parseIntLiteral(clean);
        return null;
    }

    private static @Nullable InferredType parseIntLiteral(@NotNull String text)
    {
        int radix = 10;
        String digits = text;
        if (text.startsWith("0x") || text.startsWith("0X"))
        {
            radix = 16;
            digits = text.substring(2);
        }
        else if (text.startsWith("0o") || text.startsWith("0O"))
        {
            radix = 8;
            digits = text.substring(2);
        }
        else if (text.startsWith("0b") || text.startsWith("0B"))
        {
            radix = 2;
            digits = text.substring(2);
        }

        String suffix = "";
        for (String candidate : new String[]{"ull", "ul", "ll", "u", "l"})
        {
            if (digits.length() > candidate.length()
                && digits.regionMatches(true, digits.length() - candidate.length(), candidate, 0, candidate.length()))
            {
                char before = digits.charAt(digits.length() - candidate.length() - 1);
                if (Character.digit(before, radix) >= 0)
                {
                    suffix = candidate.toLowerCase();
                    digits = digits.substring(0, digits.length() - candidate.length());
                    break;
                }
            }
        }

        BigInteger value;
        try
        {
            value = new BigInteger(digits, radix);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
        String name = switch (suffix)
        {
            case "l" -> "long";
            case "ll" -> "int128";
            case "u" -> "uint";
            case "ul" -> "ulong";
            case "ull" -> "uint128";
            default -> radix == 10 ? "int" : "uint";
        };
        return InferredType.intLiteral(value, name);
    }

    private static @Nullable InferredType parseFloatLiteral(@NotNull String text)
    {
        String name = "double";
        String digits = text;
        if (digits.endsWith("f") || digits.endsWith("F"))
        {
            name = "float";
            digits = digits.substring(0, digits.length() - 1);
        }
        else if (digits.endsWith("d") || digits.endsWith("D"))
        {
            digits = digits.substring(0, digits.length() - 1);
        }
        try
        {
            return InferredType.floatLiteral(Double.parseDouble(digits), name);
        }
        catch (NumberFormatException e)
        {
            return InferredType.unparsedFloatLiteral(name);
        }
    }

    private static @Nullable InferredType inferKeyword(@NotNull String text)
    {
        String clean = text.strip();
        if (clean.equals("true") || clean.equals("false")) return InferredType.boolType(true);
        if (clean.equals("null")) return InferredType.nullType();
        return null;
    }

    private static @Nullable InferredType inferString(@NotNull String text)
    {
        String clean = text.strip();
        if (clean.startsWith("'")) return parseCharLiteral(clean);
        if (clean.startsWith("\"") || clean.startsWith("`")) return InferredType.stringLiteral();
        return null;
    }

    private static @Nullable InferredType parseCharLiteral(@NotNull String text)
    {
        if (!text.endsWith("'") || text.length() < 3) return null;
        String inner = text.substring(1, text.length() - 1);
        BigInteger value = parseCharValue(inner);
        int width = inner.startsWith("\\") ? -1 : inner.codePointCount(0, inner.length());
        if (width == 1) return InferredType.charLiteral(value, "char");
        if (width == 2) return InferredType.charLiteral(null, "ushort");
        if (width == 4) return InferredType.charLiteral(null, "uint");
        if (width == 8) return InferredType.charLiteral(null, "ulong");
        if (value != null) return InferredType.charLiteral(value, "char");
        return null;
    }

    private static @Nullable BigInteger parseCharValue(@NotNull String inner)
    {
        try
        {
            if (inner.startsWith("\\"))
            {
                if (inner.length() == 2)
                {
                    return switch (inner.charAt(1))
                    {
                        case 'n' -> BigInteger.valueOf(10);
                        case 't' -> BigInteger.valueOf(9);
                        case 'r' -> BigInteger.valueOf(13);
                        case '0' -> BigInteger.ZERO;
                        case 'a' -> BigInteger.valueOf(7);
                        case 'b' -> BigInteger.valueOf(8);
                        case 'f' -> BigInteger.valueOf(12);
                        case 'v' -> BigInteger.valueOf(11);
                        case '\\' -> BigInteger.valueOf(92);
                        case '\'' -> BigInteger.valueOf(39);
                        case '"' -> BigInteger.valueOf(34);
                        default -> null;
                    };
                }
                if ((inner.startsWith("\\x") || inner.startsWith("\\X")) && inner.length() == 4)
                {
                    return new BigInteger(inner.substring(2), 16);
                }
                if ((inner.startsWith("\\u") || inner.startsWith("\\U")) && inner.length() > 2)
                {
                    return new BigInteger(inner.substring(2), 16);
                }
                return null;
            }
            if (inner.codePointCount(0, inner.length()) == 1) return BigInteger.valueOf(inner.codePointAt(0));
            return null;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static @Nullable InferredType inferUnary(@NotNull C3UnaryExpr unary, int depth)
    {
        if (unary.getUnaryOp().getType() != null)
        {
            // Explicit cast: (Type)expr
            return kindOf(unary.getUnaryOp().getType().getText());
        }
        String op = unary.getUnaryOp().getText().strip();
        C3Expr operand = unary.getExpr();
        if (operand == null) return null;
        InferredType inner = infer(operand, depth + 1);
        if (inner == null) return null;
        return switch (op)
        {
            case "-" -> negate(inner);
            case "+" -> numericOrNull(inner);
            case "!" -> InferredType.boolType(false);
            case "~" -> numericOrNull(inner);
            case "++", "--" -> numericOrNull(inner);
            case "&" -> InferredType.of(InferredType.Kind.POINTER, inner.getName() + "*");
            case "*" ->
            {
                if (inner.getKind() == InferredType.Kind.POINTER) yield kindOf(stripOnePointer(inner.getName()));
                yield null;
            }
            default -> null;
        };
    }

    private static @Nullable InferredType negate(@Nullable InferredType inner)
    {
        if (inner == null) return null;
        if (inner.getKind() == InferredType.Kind.INT && inner.getIntValue() != null)
        {
            return InferredType.intLiteral(inner.getIntValue().negate(), inner.getName());
        }
        if (inner.getKind() == InferredType.Kind.FLOAT && inner.getFloatValue() != null)
        {
            return InferredType.floatLiteral(-inner.getFloatValue(), inner.getName());
        }
        if (inner.getKind() == InferredType.Kind.CHAR && inner.getIntValue() != null)
        {
            return InferredType.charLiteral(inner.getIntValue().negate(), inner.getName());
        }
        return numericOrNull(inner);
    }

    private static @Nullable InferredType numericOrNull(@Nullable InferredType inner)
    {
        if (inner == null) return null;
        return switch (inner.getKind())
        {
            case INT, FLOAT, CHAR -> inner.isLiteral() ? inner : InferredType.of(inner.getKind(), inner.getName());
            default -> null;
        };
    }

    private static @NotNull String stripOnePointer(@NotNull String name)
    {
        String clean = normalize(name);
        if (clean.endsWith("*")) return clean.substring(0, clean.length() - 1).strip();
        return clean;
    }

    private static @Nullable InferredType inferBinary(@NotNull C3BinaryExpr binary, int depth)
    {
        String op = directOperator(binary);
        if (op == null) return null;
        C3Expr left = binary.getLeft();
        C3Expr right = binary.getRight();
        if (right == null) return null;
        if (op.equals("==") || op.equals("!=") || op.equals("<") || op.equals(">")
            || op.equals("<=") || op.equals(">=") || op.equals("&&") || op.equals("||"))
        {
            return InferredType.boolType(false);
        }
        // Assignments (including compound ones) are checked at statement level.
        if (op.endsWith("=")) return null;
        if (op.equals("?:") || op.equals("??"))
        {
            InferredType leftType = infer(left, depth + 1);
            InferredType rightType = infer(right, depth + 1);
            if (leftType != null && rightType != null && leftType.getName().equals(rightType.getName()))
            {
                return leftType.isLiteral() && rightType.isLiteral() ? leftType : InferredType.of(leftType.getKind(), leftType.getName());
            }
            return null;
        }
        InferredType leftType = infer(left, depth + 1);
        InferredType rightType = infer(right, depth + 1);
        if (leftType == null || rightType == null) return null;
        return arithmetic(leftType, rightType, op);
    }

    /**
     * The operator token between the operands, e.g. {@code "="}, {@code "+"}, {@code "=="}.
     * The operator may be wrapped in a {@code C3BinaryOp} element.
     */
    static @Nullable String directOperator(@NotNull C3BinaryExpr binary)
    {
        for (ASTNode child : binary.getNode().getChildren(null))
        {
            if (child.getPsi() instanceof C3BinaryOp)
            {
                String text = child.getText();
                if (text != null && !text.isBlank()) return text.strip();
            }
        }
        C3Expr left = binary.getLeft();
        C3Expr right = binary.getRight();
        int leftEnd = left.getTextRange().getEndOffset();
        int rightStart = right != null ? right.getTextRange().getStartOffset() : binary.getTextRange().getEndOffset();
        StringBuilder op = new StringBuilder();
        for (ASTNode child : binary.getNode().getChildren(null))
        {
            PsiElement psi = child.getPsi();
            if (!(psi instanceof LeafPsiElement) || psi instanceof PsiWhiteSpace) continue;
            int start = child.getTextRange().getStartOffset();
            if (start >= leftEnd && child.getTextRange().getEndOffset() <= rightStart)
            {
                op.append(child.getText());
            }
        }
        String result = op.toString().strip();
        return result.isEmpty() ? null : result;
    }

    private static @Nullable InferredType arithmetic(@NotNull InferredType left, @NotNull InferredType right, @NotNull String op)
    {
        boolean pointerOp = op.equals("+") || op.equals("-");
        if (pointerOp && left.getKind() == InferredType.Kind.POINTER && right.getKind() == InferredType.Kind.INT)
        {
            return InferredType.of(InferredType.Kind.POINTER, left.getName());
        }
        if (pointerOp && right.getKind() == InferredType.Kind.POINTER && left.getKind() == InferredType.Kind.INT)
        {
            return InferredType.of(InferredType.Kind.POINTER, right.getName());
        }
        boolean leftNum = isNumeric(left);
        boolean rightNum = isNumeric(right);
        if (!leftNum || !rightNum) return null;
        boolean floatSide = left.getKind() == InferredType.Kind.FLOAT || right.getKind() == InferredType.Kind.FLOAT;
        if (floatSide)
        {
            Double a = floatOperand(left);
            Double b = floatOperand(right);
            String name = (left.getName().equals("float") && right.getName().equals("float")) ? "float" : "double";
            if (a != null && b != null)
            {
                Double computed = computeFloat(a, b, op);
                if (computed != null) return InferredType.floatLiteral(computed, name);
            }
            return InferredType.of(InferredType.Kind.FLOAT, name);
        }
        if (left.isLiteral() && right.isLiteral() && left.getIntValue() != null && right.getIntValue() != null)
        {
            BigInteger computed = computeInt(left.getIntValue(), right.getIntValue(), op);
            if (computed != null) return InferredType.intLiteral(computed, "int");
            return null;
        }
        if (left.isLiteral() && !right.isLiteral()) return InferredType.of(right.getKind(), right.getName());
        if (right.isLiteral() && !left.isLiteral()) return InferredType.of(left.getKind(), left.getName());
        if (left.getName().equals(right.getName())) return InferredType.of(left.getKind(), left.getName());
        return null;
    }

    private static boolean isNumeric(@NotNull InferredType type)
    {
        return type.getKind() == InferredType.Kind.INT
            || type.getKind() == InferredType.Kind.FLOAT
            || type.getKind() == InferredType.Kind.CHAR;
    }

    private static @Nullable Double floatOperand(@NotNull InferredType type)
    {
        if (type.getKind() == InferredType.Kind.FLOAT) return type.getFloatValue();
        if (type.getIntValue() != null) return type.getIntValue().doubleValue();
        return null;
    }

    private static @Nullable Double computeFloat(double a, double b, @NotNull String op)
    {
        return switch (op)
        {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> b == 0 ? null : a / b;
            case "%" -> b == 0 ? null : a % b;
            default -> null;
        };
    }

    private static @Nullable BigInteger computeInt(@NotNull BigInteger a, @NotNull BigInteger b, @NotNull String op)
    {
        return switch (op)
        {
            case "+" -> a.add(b);
            case "-" -> a.subtract(b);
            case "*" -> a.multiply(b);
            case "/" -> b.equals(BigInteger.ZERO) ? null : a.divide(b);
            case "%" -> b.equals(BigInteger.ZERO) ? null : a.remainder(b);
            case "<<", ">>" -> shift(a, b, op.equals("<<"));
            case "&" -> a.and(b);
            case "|" -> a.or(b);
            case "^" -> a.xor(b);
            default -> null;
        };
    }

    private static @Nullable BigInteger shift(@NotNull BigInteger value, @NotNull BigInteger amount, boolean left)
    {
        int shift;
        try
        {
            shift = amount.intValueExact();
        }
        catch (ArithmeticException e)
        {
            return null;
        }
        if (shift < 0 || shift > 1000000) return null;
        return left ? value.shiftLeft(shift) : value.shiftRight(shift);
    }

    private static @Nullable InferredType inferTernary(@NotNull C3TernaryExpr ternary, int depth)
    {
        List<C3Expr> branches = PsiTreeUtil.getChildrenOfTypeAsList(ternary, C3Expr.class);
        if (branches.size() < 3) return null;
        InferredType second = infer(branches.get(1), depth + 1);
        InferredType third = infer(branches.get(2), depth + 1);
        if (second != null && third != null && second.getName().equals(third.getName()))
        {
            return second.isLiteral() && third.isLiteral() ? second : InferredType.of(second.getKind(), second.getName());
        }
        return null;
    }

    private static @Nullable InferredType inferPathIdent(@NotNull C3PathIdentExpr pathIdentExpr, int depth)
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
        String declared = declaredTypeText(resolved);
        if (declared != null) return kindOf(declared);
        if (resolved instanceof C3ConstDeclarationStmt constDecl)
        {
            C3Expr init = constDecl.getExpr();
            if (init != null) return infer(init, depth + 1);
        }
        return null;
    }

    private static @Nullable InferredType inferPathConst(@NotNull C3PathConstExpr pathConst, int depth)
    {
        PsiElement resolved;
        try
        {
            resolved = pathConst.getPathConst().getReference().resolve();
        }
        catch (Exception e)
        {
            return null;
        }
        if (resolved == null) return null;
        String declared = declaredTypeText(resolved);
        if (declared != null) return kindOf(declared);
        if (resolved instanceof C3EnumConstant enumConstant)
        {
            return enumTypeOf(enumConstant);
        }
        if (resolved instanceof C3ConstDeclarationStmt constDecl && constDecl.getExpr() != null)
        {
            return infer(constDecl.getExpr(), depth + 1);
        }
        return null;
    }

    private static @Nullable InferredType inferEnumAccess(@NotNull C3EnumAccessExpr enumAccess)
    {
        C3BaseType baseType = enumAccess.getBaseType();
        if (baseType == null) return null;
        return InferredType.of(InferredType.Kind.NAMED, baseType.getText().strip());
    }

    private static @Nullable InferredType enumTypeOf(@NotNull C3EnumConstant enumConstant)
    {
        C3EnumDeclaration declaration = PsiTreeUtil.getParentOfType(enumConstant, C3EnumDeclaration.class);
        if (declaration == null || declaration.getTypeName() == null) return null;
        return InferredType.of(InferredType.Kind.NAMED, declaration.getTypeName().getText().strip());
    }

    /**
     * Raw declared type text for locals, parameters and explicit const types.
     */
    public static @Nullable String declaredTypeText(@NotNull PsiElement resolved)
    {
        if (resolved instanceof C3LocalDeclAfterType)
        {
            C3LocalDeclarationStmt stmt =
                PsiTreeUtil.getParentOfType(resolved, C3LocalDeclarationStmt.class);
            if (stmt == null || stmt.getOptionalType() == null || stmt.getOptionalType().getType() == null) return null;
            return stmt.getOptionalType().getType().getText();
        }
        if (resolved instanceof C3Parameter parameter)
        {
            return parameter.getType() != null ? parameter.getType().getText() : null;
        }
        if (resolved instanceof C3ParamDecl paramDecl)
        {
            C3Type type = paramDecl.getParameter() != null ? paramDecl.getParameter().getType() : null;
            return type != null ? type.getText() : null;
        }
        if (resolved instanceof C3ConstDeclarationStmt constDecl)
        {
            return constDecl.getType() != null ? constDecl.getType().getText() : null;
        }
        if (resolved instanceof C3EnumConstant enumConstant)
        {
            InferredType enumType = enumTypeOf(enumConstant);
            return enumType != null ? enumType.getName() : null;
        }
        return null;
    }

    private static @Nullable InferredType inferCall(@NotNull C3CallExpr call, int depth)
    {
        if (call.getCallExprTail() == null || call.getCallExprTail().getCallInvocation() == null)
        {
            // Field access like `a.b`: resolve the member itself.
            C3AccessIdent accessIdent = call.getCallExprTail() != null ? call.getCallExprTail().getAccessIdent() : null;
            if (accessIdent == null) return null;
            PsiElement resolved = accessIdent.getReference().resolve();
            if (resolved instanceof C3StructMemberDeclaration member && member.getStructPathType() != null)
            {
                return kindOf(member.getStructPathType().getFullName());
            }
            return null;
        }
        C3Expr callee = call.getExpr();
        if (callee instanceof C3PathIdentExpr pathIdentExpr)
        {
            C3PathIdent pathIdent = pathIdentExpr.getPathIdent();
            PsiElement resolved;
            try
            {
                resolved = pathIdent.getReference().resolve();
            }
            catch (Exception e)
            {
                return null;
            }
            if (resolved instanceof C3FuncDef funcDef) return returnTypeOf(funcDef);
            if (resolved instanceof C3MacroDefinition macro) return returnTypeOf(macro);
            return null;
        }
        if (callee instanceof C3PathAtIdentExpr pathAtIdentExpr)
        {
            // `@macro(args)` calls.
            for (PsiReference reference : pathAtIdentExpr.getPathAtIdent().getReferences())
            {
                PsiElement resolved;
                try
                {
                    resolved = reference.resolve();
                }
                catch (Exception e)
                {
                    continue;
                }
                if (resolved instanceof C3CallablePsiElement callable) return returnTypeOf(callable);
            }
            return null;
        }
        if (callee instanceof C3CallExpr inner && depth < MAX_DEPTH)
        {
            // Chained access like `a.b().c`: resolve `c` against the inner call's type.
            C3AccessIdent outerIdent = call.getCallExprTail().getAccessIdent();
            InferredType innerType = infer(inner, depth + 1);
            if (outerIdent == null || innerType == null) return null;
            if (innerType.getKind() != InferredType.Kind.NAMED) return null;
            PsiElement resolved = outerIdent.getReference().resolve();
            if (resolved instanceof C3FuncDef funcDef) return returnTypeOf(funcDef);
            if (resolved instanceof C3StructMemberDeclaration member && member.getStructPathType() != null)
            {
                return kindOf(member.getStructPathType().getFullName());
            }
            return null;
        }
        return null;
    }

    private static @Nullable InferredType returnTypeOf(@NotNull C3CallablePsiElement callable)
    {
        ShortType returnType = callable.getReturnType();
        if (returnType == null || returnType.getValue() == null) return null;
        return kindOf(returnType.getValue());
    }

    // ------------------------------------------------------------------
    // Function structure helpers for the annotator
    // ------------------------------------------------------------------

    /**
     * Owning {@code fn} for a return statement, or {@code null} when the
     * return belongs to a lambda or macro body. Note the function body is a
     * sibling of {@code C3FuncDef} under {@code C3FuncDefinition}, not a child.
     */
    public static @Nullable C3FuncDef enclosingFunction(@NotNull C3ReturnStmt ret)
    {
        C3FuncDefinition definition = PsiTreeUtil.getParentOfType(
            ret,
            C3FuncDefinition.class,
            true,
            C3LambdaDeclExpr.class,
            C3LambdaDeclShortExpr.class,
            C3MacroDefinition.class);
        return definition != null ? definition.getFuncDef() : null;
    }

    private static final java.util.Set<String> ASSIGN_OPERATORS = java.util.Set.of(
        "=",
        "+=", "-=", "*=", "/=", "%=",
        "<<=", ">>=",
        "&=", "|=", "^=");

    /**
     * Assignment operator of a binary expression ({@code "="}, {@code "+="}, ...), or {@code null}.
     */
    public static @Nullable String assignmentOperator(@NotNull C3BinaryExpr binary)
    {
        String op = directOperator(binary);
        return op != null && ASSIGN_OPERATORS.contains(op) ? op : null;
    }
}
