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
import org.c3lang.intellij.psi.C3CallArgList;
import org.c3lang.intellij.psi.C3CallExpr;
import org.c3lang.intellij.psi.C3CallExprTail;
import org.c3lang.intellij.psi.C3CallInvocation;
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
import org.c3lang.intellij.psi.C3StructBody;
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
import java.util.Collection;
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
        INT_TYPES.put("isz", new int[]{64, 1});
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
        return "Cannot assign '" + mismatch.sourceName + "' to '" + mismatch.targetName + "'."
            + unwrapHint(mismatch.sourceName);
    }

    /**
     * Hint appended when an Optional value meets a plain type.
     */
    private static @NotNull String unwrapHint(@NotNull String sourceName)
    {
        if (isOptionalName(sourceName))
        {
            return " Use '!' to rethrow, '!!' to force unwrap or '??' for a default value.";
        }
        return "";
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
        return "Cannot return '" + mismatch.sourceName + "' from function returning '" + mismatch.targetName + "'."
            + unwrapHint(mismatch.sourceName);
    }

    // ------------------------------------------------------------------
    // Explicit casts: (Type)expr
    // ------------------------------------------------------------------

    /**
     * Diagnostic for an explicit cast, or {@code null} when the cast is fine.
     * Rules follow the C3 specification ("Cast expression"): numeric to numeric,
     * pointer to pointer, pointer to/from a pointer-sized integer, vector/array
     * with the same element type and size, alias/typedef chains, and
     * interface to/from {@code any}. Anything else is a compile error; casts
     * involving {@code any}/interfaces carry a runtime check and produce a
     * warning instead.
     */
    public static @Nullable CastDiagnostic checkCast(
            @NotNull Project project,
            @Nullable ModuleName contextModule,
            @NotNull String targetText,
            @Nullable InferredType source)
    {
        return checkCast(project, contextModule, targetText, source, null);
    }

    /**
     * @param operand the cast operand, used to tell simple expressions from
     *                complex ones for the narrowing warning; may be {@code null}.
     */
    public static @Nullable CastDiagnostic checkCast(
            @NotNull Project project,
            @Nullable ModuleName contextModule,
            @NotNull String targetText,
            @Nullable InferredType source,
            @Nullable C3Expr operand)
    {
        if (source == null) return null;
        // Compile-time type parameters are opaque until instantiation.
        if (isComptimeParam(targetText) || isComptimeParam(source.getName())) return null;
        String target = stripOptional(normalize(targetText));
        if (target.isEmpty()) return null;
        String sourceName = normalize(source.getName());

        // Discarding a value is always fine: (void)expr.
        if (target.equals("void")) return null;

        // Casting an Optional always lifts to an Optional result, propagating
        // any fault: (T)expr? has type T?. Whether that fits its context is
        // decided by assignment checking on the inferred T?.
        if (isOptionalName(sourceName))
        {
            String liftedTarget = isOptionalName(normalize(targetText)) ? target : target + "?";
            return checkCast(project, contextModule, liftedTarget, kindOf(stripOptional(sourceName)), operand);
        }

        // Resolve alias/typedef chains on both sides first (§2.5): after
        // resolution the names may simply match.
        String resolvedTarget = resolveCastType(target, project, contextModule);
        String resolvedSource = resolveCastType(sourceName, project, contextModule);
        InferredType effectiveSource = source.getName().equals(resolvedSource) ? source : kindOf(resolvedSource);

        if (namesEqual(resolvedTarget, resolvedSource)) return null;

        boolean targetNumeric = isNumericName(resolvedTarget);
        boolean sourceNumeric = isNumericKind(effectiveSource) || isNumericName(resolvedSource);
        if (targetNumeric && sourceNumeric)
        {
            return narrowingWarning(resolvedTarget, effectiveSource, operand);
        }

        boolean targetPointer = isPointerLikeName(resolvedTarget);
        boolean sourcePointer = effectiveSource.getKind() == InferredType.Kind.POINTER
            || isPointerLikeName(resolvedSource);
        if (targetPointer && sourcePointer) return null;
        if (effectiveSource.getKind() == InferredType.Kind.POINTER && isIntegerName(resolvedTarget))
        {
            if (isPointerSizedIntName(resolvedTarget)) return null;
            return CastDiagnostic.error("Cannot cast '" + sourceName + "' to '" + shortName(target)
                + "': only pointer-sized integers (iptr, uptr) can hold a pointer.");
        }
        if (targetPointer && isIntegerName(resolvedSource))
        {
            if (isPointerSizedIntName(resolvedSource)) return null;
            // A literal zero is the null pointer constant.
            if (effectiveSource.isLiteral() && BigInteger.ZERO.equals(effectiveSource.getIntValue())) return null;
            return CastDiagnostic.error("Cannot cast '" + sourceName + "' to '" + shortName(target)
                + "': only pointer-sized integers (iptr, uptr) convert to a pointer.");
        }

        if (vectorCastCompatible(resolvedTarget, resolvedSource)) return null;

        if (effectiveSource.getKind() == InferredType.Kind.BOOL || resolvedSource.equals("bool"))
        {
            if (resolvedTarget.equals("bool")) return null;
            if (isIntegerName(resolvedTarget)) return null;
            return CastDiagnostic.error("Cannot cast '" + sourceName + "' to '" + shortName(target) + "'.");
        }
        if (resolvedTarget.equals("bool"))
        {
            if (isIntegerName(resolvedSource) || effectiveSource.getKind() == InferredType.Kind.BOOL) return null;
            return CastDiagnostic.error("Cannot cast '" + sourceName + "' to 'bool'.");
        }

        if (isStringName(resolvedTarget) || isStringKind(effectiveSource))
        {
            if (isStringCompatible(resolvedTarget, effectiveSource, resolvedSource)) return null;
            return CastDiagnostic.error("Cannot cast '" + sourceName + "' to '" + shortName(target) + "'.");
        }

        if (effectiveSource.getKind() == InferredType.Kind.NULL)
        {
            if (targetPointer || target.endsWith("?") || target.endsWith("!")) return null;
            return CastDiagnostic.error("Cannot cast 'null' to '" + shortName(target) + "'.");
        }
        if (effectiveSource.getKind() == InferredType.Kind.VOID)
        {
            return CastDiagnostic.error("Cannot cast 'void' to '" + shortName(target) + "'.");
        }

        if (resolvedTarget.equals("any") || resolvedSource.equals("any"))
        {
            return CastDiagnostic.runtimeWarning(target, sourceName);
        }

        boolean targetInterface = isInterfaceName(resolvedTarget, project);
        boolean sourceInterface = isInterfaceName(resolvedSource, project);
        if (targetInterface && sourceInterface)
        {
            return CastDiagnostic.error("Cannot cast interface '" + sourceName + "' to interface '"
                + shortName(target) + "' directly, convert through 'any' first.");
        }
        if (targetInterface || sourceInterface)
        {
            if (targetInterface && staticallyImplements(resolvedSource, resolvedTarget, project)) return null;
            return CastDiagnostic.runtimeWarning(target, sourceName);
        }

        boolean targetEnum = isEnumName(resolvedTarget, project);
        boolean sourceEnum = isEnumName(resolvedSource, project);
        if (targetEnum || sourceEnum)
        {
            if (targetEnum && sourceEnum) return CastDiagnostic.error("Cannot cast enum '" + sourceName
                + "' to enum '" + shortName(target) + "'.");
            if (isIntegerName(targetEnum ? resolvedSource : resolvedTarget)
                || isNumericKind(targetEnum ? effectiveSource : kindOf(resolvedTarget))) return null;
            return CastDiagnostic.error("Cannot cast '" + sourceName + "' to '" + shortName(target) + "'.");
        }

        if (isStructName(resolvedTarget, project) && isStructName(resolvedSource, project))
        {
            if (isSubstructOf(resolvedSource, resolvedTarget, project)) return null;
            return CastDiagnostic.error("Cannot cast struct '" + sourceName + "' to struct '"
                + shortName(target) + "': no substruct relation, use an explicit conversion instead.");
        }
        if (arraySubstructCast(resolvedTarget, resolvedSource, project))
        {
            return CastDiagnostic.error("Cannot cast array of substruct '" + sourceName + "' to array of '"
                + shortName(target) + "': substruct arrays never convert, not even with an explicit cast.");
        }

        // One side (or both) is an unknown named type: cannot prove it is
        // forbidden, so stay silent instead of false-positive.
        if (isUnresolvableName(resolvedTarget, project) || isUnresolvableName(resolvedSource, project)) return null;

        return CastDiagnostic.error("Cannot cast '" + sourceName + "' to '" + shortName(target) + "'.");
    }

    /**
     * Severity-tagged cast diagnostic.
     */
    public static final class CastDiagnostic
    {
        /**
         * True for the runtime-checked {@code any}/interface warning, false for a hard error.
         */
        public final boolean warning;
        public final @NotNull String message;

        private CastDiagnostic(boolean warning, @NotNull String message)
        {
            this.warning = warning;
            this.message = message;
        }

        static @NotNull CastDiagnostic error(@NotNull String message)
        {
            return new CastDiagnostic(false, message);
        }

        static @NotNull CastDiagnostic runtimeWarning(@NotNull String target, @NotNull String sourceName)
        {
            return new CastDiagnostic(true, "Cast from '" + sourceName + "' to '" + shortName(target)
                + "' is checked at runtime and may fail.");
        }

        static @NotNull CastDiagnostic narrowingWarning(@NotNull String shortTarget)
        {
            return new CastDiagnostic(true, "Cast to '" + shortTarget
                + "' may silently lose precision for a non-constant expression.");
        }
    }

    private static boolean isNumericName(@NotNull String typeName)
    {
        String shortTarget = shortName(typeName);
        if (INT_TYPES.containsKey(shortTarget) || FLOAT_TYPES.containsKey(shortTarget)) return true;
        return shortTarget.equals("char") || shortTarget.equals("ichar") || shortTarget.equals("bool");
    }

    private static boolean isNumericKind(@NotNull InferredType type)
    {
        return type.getKind() == InferredType.Kind.INT
            || type.getKind() == InferredType.Kind.FLOAT
            || type.getKind() == InferredType.Kind.CHAR;
    }

    private static boolean isIntegerName(@NotNull String typeName)
    {
        String shortTarget = shortName(typeName);
        if (INT_TYPES.containsKey(shortTarget)) return true;
        return shortTarget.equals("char") || shortTarget.equals("ichar");
    }

    private static boolean isPointerName(@NotNull String typeName)
    {
        String clean = normalize(typeName);
        return clean.endsWith("*") && !clean.endsWith("**") && parseArrayPointer(clean) == null
            || clean.endsWith("**");
    }

    private static boolean isPointerLikeName(@NotNull String typeName)
    {
        String clean = normalize(typeName);
        if (clean.endsWith("*")) return true;
        if (clean.endsWith("[]")) return true;
        return parseArray(clean) != null || parseVector(clean) != null;
    }

    private static boolean isPointerSizedIntName(@NotNull String typeName)
    {
        return switch (shortName(normalize(typeName)))
        {
            case "iptr", "uptr", "sz", "isz", "usz", "long", "ulong" -> true;
            default -> false;
        };
    }

    private static boolean isStringName(@NotNull String typeName)
    {
        return switch (shortName(normalize(typeName)))
        {
            case "String", "ZString" -> true;
            default -> false;
        };
    }

    private static boolean isStringKind(@NotNull InferredType type)
    {
        if (type.getKind() == InferredType.Kind.STRING) return true;
        String name = normalize(type.getName());
        return name.equals("char[]") || name.equals("ichar[]") || name.equals("char*") || name.equals("ichar*");
    }

    private static boolean isStringCompatible(
            @NotNull String resolvedTarget, @NotNull InferredType effectiveSource, @NotNull String resolvedSource)
    {
        if (isStringKind(effectiveSource) && (isStringName(resolvedTarget) || isStringKind(kindOf(resolvedTarget)))) return true;
        if (isStringName(resolvedSource) && (isStringName(resolvedTarget) || isStringKind(kindOf(resolvedTarget)))) return true;
        return isStringName(resolvedTarget) && isPointerName(resolvedSource);
    }

    private static boolean vectorCastCompatible(@NotNull String resolvedTarget, @NotNull String resolvedSource)
    {
        VectorInfo targetVector = parseVector(resolvedTarget);
        VectorInfo sourceVector = parseVector(resolvedSource);
        if (targetVector != null || sourceVector != null)
        {
            if (targetVector == null || sourceVector == null) return false;
            if (!namesEqual(targetVector.element, sourceVector.element)) return false;
            return targetVector.size == sourceVector.size && targetVector.size >= 0;
        }
        VectorInfo targetArray = parseArray(resolvedTarget);
        VectorInfo sourceArray = parseArray(resolvedSource);
        if (targetArray == null || sourceArray == null) return false;
        if (!namesEqual(targetArray.element, sourceArray.element)) return false;
        return targetArray.size == sourceArray.size && targetArray.size >= 0;
    }

    private static @Nullable String resolveTypedefChain(
            @NotNull String typeName,
            @NotNull Project project,
            @Nullable ModuleName contextModule)
    {
        String current = typeName;
        for (int depth = 0; depth < 4; depth++)
        {
            String next = resolveTypedef(current, project, contextModule, 0);
            if (next == null) next = resolveInlineTypedef(current, project, contextModule, 0);
            if (next == null) return depth == 0 ? null : current;
            current = next;
        }
        return current;
    }

    /**
     * Full chain resolution for explicit casts: unlike implicit conversions,
     * a cast may cross any mixture of {@code alias} and (inline or distinct)
     * {@code typedef} links (spec §2.5), e.g.
     * {@code Errno -> inline CInt -> $typefrom(...) -> int}.
     * Bounded and cycle-safe; returns the input when nothing resolves.
     */
    private static @NotNull String resolveCastType(
            @NotNull String typeName,
            @NotNull Project project,
            @Nullable ModuleName contextModule)
    {
        String current = typeName;
        for (int depth = 0; depth < 6; depth++)
        {
            String next = resolveAlias(current, project, contextModule, 0);
            if (next == null) next = resolveTypedef(current, project, contextModule, 0);
            if (next == null) next = resolveInlineTypedef(current, project, contextModule, 0);
            if (next == null || namesEqual(next, current)) return current;
            current = next;
        }
        return current;
    }

    private static boolean isInterfaceName(@NotNull String typeName, @NotNull Project project)
    {
        return findTypeParent(typeName, project) instanceof C3InterfaceDefinition;
    }

    private static boolean isEnumName(@NotNull String typeName, @NotNull Project project)
    {
        return findTypeParent(typeName, project) instanceof C3EnumDeclaration;
    }

    private static boolean isStructName(@NotNull String typeName, @NotNull Project project)
    {
        PsiElement parent = findTypeParent(typeName, project);
        return parent instanceof C3StructDeclaration || parent instanceof C3BitstructDeclaration;
    }

    private static boolean isUnresolvableName(@NotNull String typeName, @NotNull Project project)
    {
        String clean = normalize(typeName);
        if (!isUserTypeName(clean)) return false;
        if (DumbService.isDumb(project)) return true;
        return findTypeParent(clean, project) == null;
    }

    private static @Nullable PsiElement findTypeParent(@NotNull String typeName, @NotNull Project project)
    {
        String clean = normalize(typeName);
        if (!isUserTypeName(clean)) return null;
        String wanted = shortName(clean);
        for (String key : StubIndex.getInstance().getAllKeys(TypeIndex.KEY, project))
        {
            if (!key.equals(wanted) && !key.endsWith("::" + wanted)) continue;
            for (C3PsiElement element : safeElements(TypeIndex.KEY, key, project))
            {
                if (!(element instanceof C3TypeName typeNameElement)) continue;
                if (!typeNameElement.getText().strip().equals(wanted)) continue;
                PsiElement parent = typeNameElement.getParent();
                if (parent instanceof C3StructDeclaration
                    || parent instanceof C3BitstructDeclaration
                    || parent instanceof C3EnumDeclaration
                    || parent instanceof C3InterfaceDefinition
                    || parent instanceof C3TypedefDecl
                    || parent instanceof C3AliasTypeDecl)
                {
                    return parent;
                }
            }
        }
        return null;
    }

    /**
     * Index lookup that tolerates stale entries for files without a stub
     * tree (e.g. indexed as plain text before C3 association): degrades to
     * empty instead of throwing into highlighting.
     */
    static @NotNull Collection<C3PsiElement> safeElements(
            @NotNull com.intellij.psi.stubs.StubIndexKey<String, C3PsiElement> key,
            @NotNull String indexKey,
            @NotNull Project project)
    {
        try
        {
            return StubIndex.getElements(
                key,
                indexKey,
                project,
                C3ProjectService.getInstance(project).getSearchScope(),
                C3PsiElement.class);
        }
        catch (Exception ignored)
        {
            return List.of();
        }
    }

    private static boolean staticallyImplements(
            @NotNull String sourceName, @NotNull String ifaceName, @NotNull Project project)
    {
        if (DumbService.isDumb(project)) return false;
        try
        {
            FullyQualifiedName source = FullyQualifiedName.parse(sourceName);
            List<FullyQualifiedName> implemented =
                org.c3lang.intellij.index.InterfaceService.INSTANCE.getImplementedInterfaces(source, project);
            String wanted = shortName(ifaceName);
            for (FullyQualifiedName candidate : implemented)
            {
                if (candidate.getName().equals(wanted) || candidate.getFullName().equals(ifaceName)) return true;
            }
        }
        catch (Exception e)
        {
            return false;
        }
        return false;
    }

    /**
     * Compile-time type parameters ({@code $Type}, {@code $Foo}): abstract
     * types provided at macro instantiation. Unknowable without expanding
     * the call, so any conversion involving them is allowed: it is checked
     * by the compiler per instantiation. Member access like
     * {@code $Type.min} stays unknown (and silent) through normal inference.
     */
    static boolean isComptimeParam(@NotNull String typeName)
    {
        return COMPTIME_PARAM_PATTERN.matcher(normalize(typeName)).find();
    }

    private static final java.util.regex.Pattern COMPTIME_PARAM_PATTERN =
        java.util.regex.Pattern.compile("\\$[A-Z]");

    /**
     * {@code void*} stays a wildcard behind {@code alias}/{@code typedef}
     * links: when either side resolves to {@code void*}, pointer conversions
     * apply as if it were written directly.
     */
    private static boolean voidStarTransparent(
            @NotNull Project project,
            @Nullable ModuleName contextModule,
            @NotNull String targetText,
            @NotNull InferredType source)
    {
        if (DumbService.isDumb(project)) return false;
        String cleanTarget = stripOptional(normalize(targetText));
        String sourceName = normalize(source.getName());
        if (!isUserTypeName(cleanTarget) && !isUserTypeName(sourceName)) return false;
        String resolvedTarget = resolveCastType(cleanTarget, project, contextModule);
        String resolvedSource = resolveCastType(sourceName, project, contextModule);
        boolean targetIsVoid = resolvedTarget.equals("void*");
        boolean sourceIsVoid = resolvedSource.equals("void*");
        if (!targetIsVoid && !sourceIsVoid) return false;
        if (targetIsVoid && sourceIsVoid) return true;
        if (targetIsVoid) return isVoidPointerCompatible(source);
        return cleanTarget.endsWith("*");
    }

    /**
     * Implicit conversion of a struct (or a pointer to it) to an interface
     * it implements, e.g. {@code File*} to {@code OutStream} when declared
     * as {@code struct File (InStream, OutStream)}. Mirrors the compiler rule
     * behind {@code MyName a = &b;}.
     */
    private static boolean interfaceAssignable(
            @NotNull Project project,
            @Nullable ModuleName contextModule,
            @NotNull String targetText,
            @NotNull InferredType source)
    {
        if (DumbService.isDumb(project)) return false;
        String cleanTarget = stripOptional(normalize(targetText));
        if (!isUserTypeName(cleanTarget)) return false;
        String resolvedIface = resolveAlias(cleanTarget, project, contextModule, 0);
        if (resolvedIface != null) cleanTarget = stripOptional(normalize(resolvedIface));
        if (!isInterfaceName(cleanTarget, project)) return false;
        String structName = interfaceSourceStruct(source);
        if (structName == null) return false;
        String resolved = resolveAlias(structName, project, contextModule, 0);
        if (resolved != null) structName = resolved;
        return staticallyImplements(structName, cleanTarget, project);
    }

    /**
     * Struct behind a pointer source for interface conversion, e.g.
     * {@code File} for {@code File*}. Only pointers convert implicitly: a
     * struct value would need an explicit address-of (an rvalue would
     * otherwise dangle behind the interface reference).
     */
    private static @Nullable String interfaceSourceStruct(@NotNull InferredType source)
    {
        if (source.getKind() != InferredType.Kind.POINTER) return null;
        String pointee = normalize(source.getName());
        while (pointee.endsWith("*")) pointee = pointee.substring(0, pointee.length() - 1).strip();
        if (!isUserTypeName(pointee)) return null;
        return pointee;
    }

    private static boolean isSubstructOf(
            @NotNull String childName, @NotNull String parentName, @NotNull Project project)
    {
        if (DumbService.isDumb(project)) return false;
        try
        {
            FullyQualifiedName child = FullyQualifiedName.parse(childName);
            List<C3StructDeclaration> declarations =
                org.c3lang.intellij.index.InterfaceService.INSTANCE.findStructDeclarations(child, project);
            String wanted = shortName(parentName);
            for (C3StructDeclaration declaration : declarations)
            {
                C3StructBody body = declaration.getStructBody();
                if (body == null) continue;
                for (C3StructMemberDeclaration member : body.getStructMemberDeclarationList())
                {
                    // An inline substruct member is written as a bare type (`inline Foo;`).
                    if (member.getIdentifierList() != null) continue;
                    if (member.getStructBody() != null || member.getBitstructBody() != null) continue;
                    C3Type memberType = member.getType();
                    if (memberType == null) continue;
                    if (shortName(normalize(memberType.getText())).equals(wanted)) return true;
                }
            }
        }
        catch (Exception e)
        {
            return false;
        }
        return false;
    }

    private static boolean arraySubstructCast(
            @NotNull String resolvedTarget, @NotNull String resolvedSource, @NotNull Project project)
    {
        String targetElement = arrayElementType(resolvedTarget);
        String sourceElement = arrayElementType(resolvedSource);
        if (targetElement == null || sourceElement == null) return false;
        if (namesEqual(targetElement, sourceElement)) return false;
        return isStructName(targetElement, project) && isStructName(sourceElement, project)
            && (isSubstructOf(sourceElement, targetElement, project)
                || isSubstructOf(targetElement, sourceElement, project));
    }

    /**
     * Warning for {@code (narrow_type)complex_expr}: the value may silently
     * lose precision even though the compiler accepts the cast. Literals and
     * simple expressions are skipped.
     */
    private static @Nullable CastDiagnostic narrowingWarning(
            @NotNull String resolvedTarget, @NotNull InferredType effectiveSource, @Nullable C3Expr operand)
    {
        if (operand == null || isSimpleOperand(operand)) return null;
        if (effectiveSource.isLiteral()) return null;
        String shortTarget = shortName(resolvedTarget);
        if (shortTarget.equals("bool")) return null;
        int[] targetBits = INT_TYPES.get(shortTarget);
        if (targetBits == null && !shortTarget.equals("char") && !shortTarget.equals("ichar")) return null;
        int targetWidth = targetBits != null ? targetBits[0] : 8;
        Integer sourceWidth = numericWidth(effectiveSource);
        // int -> float widens implicitly and is always fine.
        if (FLOAT_TYPES.containsKey(shortTarget)) return null;
        if (effectiveSource.getKind() == InferredType.Kind.FLOAT && targetBits == null) return null;
        if (sourceWidth != null && sourceWidth <= targetWidth) return null;
        return CastDiagnostic.narrowingWarning(shortTarget);
    }

    private static @Nullable Integer numericWidth(@NotNull InferredType type)
    {
        String shortSource = shortName(type.getName());
        int[] intBits = INT_TYPES.get(shortSource);
        if (intBits != null) return intBits[0];
        if (shortSource.equals("char") || shortSource.equals("ichar")) return 8;
        Integer floatBits = FLOAT_TYPES.get(shortSource);
        if (floatBits != null) return floatBits;
        return switch (type.getKind())
        {
            case INT, CHAR -> 32;
            case FLOAT -> 64;
            default -> null;
        };
    }

    private static boolean isSimpleOperand(@NotNull C3Expr operand)
    {
        if (operand instanceof C3LiteralExpr
            || operand instanceof C3StringExpr
            || operand instanceof C3KeywordExpr
            || operand instanceof C3PathIdentExpr
            || operand instanceof C3PathConstExpr) return true;
        if (operand instanceof C3GroupedExpr grouped)
        {
            C3Expr inner = grouped.getExpr();
            return inner != null && isSimpleOperand(inner);
        }
        return false;
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
        if (isOptionalName(arg.getName()) && !isOptionalName(normalize(paramTypeText)))
        {
            // Cascading: a function called with an Optional argument is only
            // executed when all Optional arguments hold results, and its own
            // result becomes Optional. Only the unwrapped types must match.
            Mismatch unwrapped = check(project, contextModule, paramTypeText, kindOf(stripOptional(arg.getName())));
            if (unwrapped == null) return null;
            // Otherwise fall through and report the Optional mismatch below.
        }
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
            + "' of type '" + mismatch.targetName + "'." + unwrapHint(mismatch.sourceName);
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
            for (C3PsiElement element : safeElements(TypeIndex.KEY, key, project))
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

    /**
     * Whether the text is the {@code void?} Optional: only usable as a
     * function return type, never as a variable.
     */
    public static boolean isVoidOptionalType(@NotNull String typeText)
    {
        String clean = normalize(typeText);
        return clean.equals("void?") || clean.equals("void!");
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
        if (isComptimeParam(target) || isComptimeParam(source.getName())) return null;
        if (voidStarTransparent(project, contextModule, target, source)) return null;
        if (interfaceAssignable(project, contextModule, target, source)) return null;
        if (isComptimeNumericLenient(target, source, project, contextModule)) return null;

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

        // Optional handling: `T?` holds either a `T` result or a fault.
        boolean targetOptional = isOptionalName(target);
        boolean sourceOptional = isOptionalName(source.getName());
        if (targetOptional && sourceOptional)
        {
            // `T?` accepts `U?` when the unwrapped result types are compatible.
            Mismatch inner = checkOnce(stripOptional(target), kindOf(stripOptional(source.getName())));
            if (inner == null) return null;
            return new Mismatch(source.getName(), targetName, null, null, -1, -1);
        }
        if (targetOptional)
        {
            // A plain value converts into the Optional's result type.
            return checkOnce(base, source);
        }
        if (sourceOptional)
        {
            // An Optional never converts to a plain type implicitly: use `!`
            // (rethrow), `!!` (force unwrap) or `?? default` to unwrap it.
            return new Mismatch(source.getName(), targetName, null, null, -1, -1);
        }

        // `void*` is a wildcard matching any pointer-like source: pointers,
        // array pointers and slices (which convert to pointers), strings,
        // `null` and `any`. A fixed array value does not decay into it.
        if (base.equals("void*"))
        {
            if (isVoidPointerCompatible(source)) return null;
            return new Mismatch(source.getName(), targetName, null, null, -1, -1);
        }

        if (source.getKind() == InferredType.Kind.INIT_LIST)
        {
            // Unreachable via check(); deny in the pure path.
            return new Mismatch(source.getName(), targetName, null, null, -1, -1);
        }

        if (arrayPointerCompatible(base, source)) return null;

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
                if (FLOAT_TYPES.containsKey(shortName(base))) return null;
                if (intAssignable(base, source)) return null;
                if (source.isLiteral() && source.getIntValue() != null && intWidth(shortName(base)) >= 0)
                {
                    return new Mismatch(source.getName(), targetName, source.getIntValue(), null, -1, -1);
                }
                break;
            case FLOAT:
                if (floatAssignable(base, source)) return null;
                if (source.isLiteral() && source.getFloatValue() != null && FLOAT_TYPES.get(shortName(base)) != null)
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
            for (C3PsiElement element : safeElements(TypeIndex.KEY, key, project))
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
        if (type == null)
        {
            // Compile-time computed right-hand side, e.g.
            // `alias CInt = $typefrom(signed_int_from_bitsize($$C_INT_SIZE));`.
            return evaluateComptimeAlias(typedefType.getExpr());
        }
        String text = type.getText();
        return text == null || text.isBlank() ? null : text.strip();
    }

    /**
     * Evaluates a compile-time alias right-hand side to a concrete builtin
     * type name. Handles the standard {@code std::core::cinterop} pattern
     * {@code $typefrom(signed_int_from_bitsize($$C_X_SIZE))} (and the
     * unsigned/legacy-capitalized variants), {@code $typefrom(X.typeid)}
     * and {@code $typefrom("name")}. Anything else returns {@code null}.
     * Pure text matching on the already-located declaration: no index access.
     */
    private static @Nullable String evaluateComptimeAlias(@Nullable C3Expr expr)
    {
        if (!(expr instanceof C3CallExpr call)) return null;
        C3CallExprTail tail = call.getCallExprTail();
        if (tail == null || tail.getCallInvocation() == null) return null;
        String callee = call.getExpr().getText().strip();
        if (!callee.equals("$typefrom") && !callee.equals("$Typefrom")) return null;
        C3CallArgList callArgs = tail.getCallInvocation().getCallArgList();
        C3ArgList args = callArgs != null ? callArgs.getArgList() : null;
        if (args == null || args.getArgList().size() != 1) return null;
        C3Expr arg = args.getArgList().get(0).getExpr();
        if (arg == null) return null;
        String inner = normalize(arg.getText());

        java.util.regex.Matcher bitsize = BITSIZE_PATTERN.matcher(inner);
        if (bitsize.matches())
        {
            boolean signed = bitsize.group(1).equals("signed");
            int bits = cAbiBitsize(bitsize.group(2));
            if (bits < 0) return null;
            return signed ? SIGNED_BY_BITS.get(bits) : UNSIGNED_BY_BITS.get(bits);
        }
        java.util.regex.Matcher typeidAccess = TYPEID_PATTERN.matcher(inner);
        if (typeidAccess.matches()) return typeidAccess.group(1);
        java.util.regex.Matcher stringName = QUOTED_NAME_PATTERN.matcher(inner);
        if (stringName.matches()) return stringName.group(1);
        return null;
    }

    private static final java.util.regex.Pattern BITSIZE_PATTERN =
        java.util.regex.Pattern.compile("(?:[A-Za-z_][A-Za-z_0-9]*::)*(signed|unsigned)_int_from_bitsize\\(\\$\\$C_([A-Z_]+)_SIZE\\)");
    private static final java.util.regex.Pattern TYPEID_PATTERN =
        java.util.regex.Pattern.compile("([A-Za-z_][A-Za-z_0-9]*(?:::[A-Za-z_][A-Za-z_0-9]*)*)\\.typeid");
    private static final java.util.regex.Pattern QUOTED_NAME_PATTERN =
        java.util.regex.Pattern.compile("\"([A-Za-z_][A-Za-z_0-9]*(?:::[A-Za-z_][A-Za-z_0-9]*)*)\"");

    private static final java.util.Map<Integer, String> SIGNED_BY_BITS = java.util.Map.of(
        8, "ichar", 16, "short", 32, "int", 64, "long", 128, "int128");
    private static final java.util.Map<Integer, String> UNSIGNED_BY_BITS = java.util.Map.of(
        8, "char", 16, "ushort", 32, "uint", 64, "ulong", 128, "uint128");

    /**
     * Bit width of a C ABI type for the compilation target. Only
     * {@code long} differs between the data models (LP64 vs LLP64); without
     * a project target setting the host OS decides, which matches the
     * build host in the common case.
     */
    private static int cAbiBitsize(@NotNull String name)
    {
        return switch (name)
        {
            case "SHORT" -> 16;
            case "INT" -> 32;
            case "LONG_LONG" -> 64;
            case "LONG" -> isWindowsHost() ? 32 : 64;
            default -> -1;
        };
    }

    private static boolean isWindowsHost()
    {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /**
     * Leniency for aliases with a compile-time right-hand side the evaluator
     * could not resolve (e.g. the {@code CChar} ternary): when one side is
     * such an alias and the other side is numeric, allow the conversion. A
     * missed real error is preferable to blocking compilable code here.
     */
    private static boolean isComptimeNumericLenient(
            @NotNull String target,
            @NotNull InferredType source,
            @NotNull Project project,
            @Nullable ModuleName contextModule)
    {
        String cleanTarget = normalize(target);
        String cleanSource = normalize(source.getName());
        boolean targetOpaque = isUnresolvedComptimeAlias(cleanTarget, project, contextModule);
        boolean sourceOpaque = isUnresolvedComptimeAlias(cleanSource, project, contextModule);
        if (!targetOpaque && !sourceOpaque) return false;
        boolean targetNumeric = isIntegerName(cleanTarget) || isFloatType(cleanTarget);
        boolean sourceNumeric = isNumericKind(source)
            || isIntegerName(cleanSource) || isFloatType(cleanSource);
        return (targetOpaque && sourceNumeric) || (sourceOpaque && targetNumeric);
    }

    private static boolean isUnresolvedComptimeAlias(
            @NotNull String name,
            @NotNull Project project,
            @Nullable ModuleName contextModule)
    {
        if (!isUserTypeName(name)) return false;
        if (resolveAlias(name, project, contextModule, 0) != null) return false;
        return hasComptimeAliasRhs(shortName(normalize(name)), project);
    }

    /**
     * Whether an alias/typedef with this short name has a call-expression
     * right-hand side that looks typeid-producing ({@code $typefrom},
     * {@code typeid}, {@code bitsize}). Pure index scan, no resolution.
     */
    private static boolean hasComptimeAliasRhs(@NotNull String shortName, @NotNull Project project)
    {
        if (DumbService.isDumb(project)) return false;
        try
        {
            for (String key : StubIndex.getInstance().getAllKeys(TypeIndex.KEY, project))
            {
                if (!key.equals(shortName) && !key.endsWith("::" + shortName)) continue;
                for (C3PsiElement element : safeElements(TypeIndex.KEY, key, project))
                {
                    if (!(element instanceof C3TypeName typeName)) continue;
                    if (!typeName.getText().strip().equals(shortName)) continue;
                    PsiElement parent = typeName.getParent();
                    C3TypedefType typedefType = null;
                    if (parent instanceof C3AliasTypeDecl aliasDecl)
                    {
                        if (aliasDecl.getGenericDecl() != null) continue;
                        typedefType = aliasDecl.getTypedefType();
                    }
                    else if (parent instanceof C3TypedefDecl typedefDecl)
                    {
                        if (typedefDecl.getGenericDecl() != null) continue;
                        typedefType = typedefDecl.getTypedefType();
                    }
                    if (typedefType == null || typedefType.getGenericParameters() != null) continue;
                    if (typedefType.getType() != null) continue;
                    if (!(typedefType.getExpr() instanceof C3CallExpr call)) continue;
                    String callText = call.getText().toLowerCase(java.util.Locale.ROOT);
                    if (callText.contains("typefrom") || callText.contains("typeid") || callText.contains("bitsize"))
                    {
                        return true;
                    }
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return false;
    }

    private static boolean hasInlineModifier(@NotNull PsiElement declaration)
    {
        ASTNode inline = declaration.getNode().findChildByType(C3Types.KW_INLINE);
        return inline != null;
    }

    /**
     * Array/slice/pointer conversions from {@code docs/arrays.md} (same element type required):
     * <ul>
     * <li>{@code T[]} accepts {@code T[]} and {@code T[N]*}</li>
     * <li>{@code T*} accepts {@code T[]}, {@code T[N]*} and {@code T*}</li>
     * <li>{@code T[N]*} accepts {@code T[M]*} with equal size</li>
     * </ul>
     * Fixed arrays never convert implicitly; anything else needs an explicit cast.
     */
    /**
     * Whether a value converts to the {@code void*} wildcard: any pointer
     * (plain or array pointer), any slice, strings, {@code null} and
     * {@code any}. Anything else, including fixed array values (which need
     * an explicit {@code &}), does not.
     */
    private static boolean isVoidPointerCompatible(@NotNull InferredType source)
    {
        switch (source.getKind())
        {
            case POINTER:
            case NULL:
            case STRING:
                return true;
            case BOOL:
            case CHAR:
            case INT:
            case FLOAT:
            case VOID:
            case INIT_LIST:
                return false;
            case NAMED:
                break;
        }
        String name = normalize(source.getName());
        if (name.equals("any") || name.equals("void*")) return true;
        if (name.endsWith("[]")) return true;
        return parseArrayPointer(name) != null;
    }

    private static boolean arrayPointerCompatible(@NotNull String base, @NotNull InferredType source)
    {
        String sourceName = source.getName();
        if (isVectorName(base) || isVectorName(sourceName)) return false;

        boolean targetSlice = isSliceName(base);
        boolean targetPtr = isPlainPointerName(base);
        VectorInfo targetArrayPtr = parseArrayPointer(base);
        if (!targetSlice && !targetPtr && targetArrayPtr == null) return false;

        boolean sourceSlice = isSliceName(sourceName);
        boolean sourcePtr = isPlainPointerName(sourceName);
        VectorInfo sourceArrayPtr = parseArrayPointer(sourceName);
        if (!sourceSlice && !sourcePtr && sourceArrayPtr == null) return false;

        String targetElement = targetSlice || targetPtr ? sliceOrPointerElement(base) : targetArrayPtr.element;
        String sourceElement = sourceSlice || sourcePtr ? sliceOrPointerElement(sourceName) : sourceArrayPtr.element;
        if (targetElement == null || sourceElement == null || !namesEqual(targetElement, sourceElement)) return false;

        if (targetSlice) return true;
        if (targetPtr) return true;
        // T[N]* <- T[M]* requires equal sizes (textual or numeric);
        // slices and plain pointers need an explicit cast here.
        if (sourceArrayPtr == null) return false;
        return targetArrayPtr.sizeText.equals(sourceArrayPtr.sizeText)
            || (targetArrayPtr.size >= 0 && targetArrayPtr.size == sourceArrayPtr.size);
    }

    private static boolean isSliceName(@NotNull String typeText)
    {
        String clean = normalize(typeText);
        return clean.endsWith("[]");
    }

    private static boolean isPlainPointerName(@NotNull String typeText)
    {
        String clean = normalize(typeText);
        return clean.endsWith("*") && !clean.endsWith("**") && parseArrayPointer(clean) == null;
    }

    private static @Nullable String sliceOrPointerElement(@NotNull String typeText)
    {
        String clean = normalize(typeText);
        if (clean.endsWith("[]")) return clean.substring(0, clean.length() - 2);
        if (clean.endsWith("*") && !clean.endsWith("**"))
        {
            String element = clean.substring(0, clean.length() - 1).strip();
            return element.isEmpty() ? null : element;
        }
        return null;
    }

    private static @Nullable VectorInfo parseArrayPointer(@NotNull String typeText)
    {
        // An array pointer `T[N]*`: array with a trailing star (but not a plain `T*`).
        String clean = normalize(typeText);
        if (!clean.endsWith("*") || clean.endsWith("**")) return null;
        VectorInfo array = parseArray(clean.substring(0, clean.length() - 1));
        if (array == null || array.size < 0) return null;
        return array;
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
        public final @NotNull String sizeText;

        VectorInfo(@NotNull String element, long size, @NotNull String sizeText)
        {
            this.element = element;
            this.size = size;
            this.sizeText = sizeText;
        }
    }

    private static long parseSize(@NotNull String sizeText)
    {
        if (sizeText.isEmpty() || sizeText.equals("*")) return -1;
        try
        {
            long size = Long.parseLong(sizeText);
            return size < 0 ? -1 : size;
        }
        catch (NumberFormatException e)
        {
            return -2;
        }
    }

    public static @Nullable VectorInfo parseVector(@NotNull String typeText)
    {
        java.util.regex.Matcher matcher = VECTOR_PATTERN.matcher(normalize(typeText));
        if (!matcher.matches()) return null;
        String element = matcher.group(1);
        if (element.isEmpty()) return null;
        long size = parseSize(matcher.group(2));
        if (size < -1) return null;
        return new VectorInfo(element, size, matcher.group(2));
    }

    public static boolean isIntegerType(@NotNull String typeText)
    {
        return INT_TYPES.containsKey(shortName(normalize(typeText)));
    }

    public static boolean isFloatType(@NotNull String typeText)
    {
        return FLOAT_TYPES.containsKey(shortName(normalize(typeText)));
    }

    /**
     * Element type of an array or slice ({@code int} for {@code int[4]},
     * {@code int[*]} and {@code int[]}), or {@code null}.
     */
    public static @Nullable String arrayElementType(@NotNull String typeText)
    {
        String clean = normalize(typeText);
        if (clean.endsWith("[]")) return clean.substring(0, clean.length() - 2);
        VectorInfo array = parseArray(clean);
        return array != null ? array.element : null;
    }

    public static boolean isSliceType(@NotNull String typeText)
    {
        String clean = normalize(typeText);
        return clean.endsWith("[]");
    }

    private static boolean isVectorName(@NotNull String typeText)
    {
        return VECTOR_PATTERN.matcher(normalize(typeText)).matches();
    }

    private static @Nullable VectorInfo parseArray(@NotNull String typeText)
    {
        java.util.regex.Matcher matcher = ARRAY_PATTERN.matcher(normalize(typeText));
        if (!matcher.matches()) return null;
        String element = matcher.group(1);
        if (element.isEmpty()) return null;
        long size = parseSize(matcher.group(2));
        if (size < -1) return null;
        return new VectorInfo(element, size, matcher.group(2));
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

    /**
     * Whether a type name carries the Optional suffix (`T?`, old syntax `T!`).
     */
    public static boolean isOptionalName(@NotNull String typeName)
    {
        String clean = normalize(typeName);
        return (clean.endsWith("?") || clean.endsWith("!")) && !clean.endsWith("*") && clean.length() > 1;
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
        String baseSlice = base.equals("char[*]") ? "char[]" : (base.equals("ichar[*]") ? "ichar[]" : base);
        String nameSlice = name.equals("char[*]") ? "char[]" : (name.equals("ichar[*]") ? "ichar[]" : name);
        return (nameSlice.equals("String") && (baseSlice.equals("String") || baseSlice.equals("char[]")))
            || (nameSlice.equals("char[]") && (baseSlice.equals("String") || baseSlice.equals("char[]")))
            || (nameSlice.equals("ZString") && (baseSlice.equals("ZString") || baseSlice.equals("char*")))
            || (nameSlice.equals("char*") && (baseSlice.equals("ZString") || baseSlice.equals("char*")));
    }

    private static boolean intAssignable(@NotNull String base, @NotNull InferredType source)
    {
        String shortBase = shortName(base);
        int targetWidth = intWidth(shortBase);
        if (targetWidth < 0) return false;
        if (source.isLiteral())
        {
            // Typed literal without a known value (e.g. a wide char literal):
            // only the exact type is accepted, checked by the caller.
            if (source.getIntValue() == null) return false;
            int[] targetBits = INT_TYPES.get(shortBase);
            if (targetBits != null) return fits(source.getIntValue(), targetBits);
            // `char` is an 8-bit unsigned integer.
            return shortBase.equals("char") && fitsUnsigned(source.getIntValue(), 8);
        }
        // Implicit widening: a value of a narrower (or equally wide) integer
        // type converts to the target, regardless of signedness. Narrowing a
        // wider integer type needs an explicit cast.
        int sourceWidth = intWidth(shortName(source.getName()));
        if (sourceWidth < 0) return false;
        return targetWidth >= sourceWidth;
    }

    private static boolean floatAssignable(@NotNull String base, @NotNull InferredType source)
    {
        Integer targetBits = FLOAT_TYPES.get(shortName(base));
        if (targetBits == null) return false;
        if (source.isLiteral())
        {
            Double value = source.getFloatValue();
            if (value == null) return true;
            if (value.isNaN() || value.isInfinite()) return false;
            double max = maxFloat(targetBits);
            return Math.abs(value) <= max;
        }
        Integer sourceBits = FLOAT_TYPES.get(shortName(source.getName()));
        return sourceBits != null && sourceBits <= targetBits;
    }

    /**
     * Bit width of an integer type name ({@code char} counts as unsigned 8),
     * or {@code -1} for non-integer names.
     */
    private static int intWidth(@NotNull String shortName)
    {
        int[] bits = INT_TYPES.get(shortName);
        if (bits != null) return bits[0];
        if (shortName.equals("char")) return 8;
        return -1;
    }

    private static boolean fitsUnsigned(@NotNull BigInteger value, int bits)
    {
        return value.signum() >= 0 && value.bitLength() <= bits;
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
     * Primitive names may be module-qualified (e.g. a struct member type
     * resolved to {@code mod::uint}); they are shortened to the builtin name.
     */
    public static @NotNull InferredType kindOf(@NotNull String typeText)
    {
        String text = normalize(typeText);
        String shortText = shortName(text);
        if (shortText.equals("void")) return InferredType.voidType();
        if (shortText.equals("bool")) return InferredType.boolType(false);
        if (shortText.equals("char")) return InferredType.of(InferredType.Kind.CHAR, "char");
        if (INT_TYPES.containsKey(shortText)) return InferredType.of(InferredType.Kind.INT, shortText);
        if (FLOAT_TYPES.containsKey(shortText)) return InferredType.of(InferredType.Kind.FLOAT, shortText);
        if (shortText.equals("String") || shortText.equals("ZString")) return InferredType.of(InferredType.Kind.STRING, shortText);
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
            // Explicit cast: (Type)expr. Casting an Optional lifts to an
            // Optional result: (T)expr? has type T?.
            String castText = unary.getUnaryOp().getType().getText();
            C3Expr castOperand = unary.getExpr();
            if (castOperand != null && !isOptionalName(castText))
            {
                InferredType innerCast = infer(castOperand, depth + 1);
                if (innerCast != null && isOptionalName(innerCast.getName()))
                {
                    return kindOf(normalize(castText) + "?");
                }
            }
            return kindOf(castText);
        }
        String op = unary.getUnaryOp().getText().strip();
        if (op.equals("&&"))
        {
            // Address of a temporary (rvalue), e.g. &&1: the operand type with no
            // addressability requirement.
            C3Expr tempOperand = unary.getExpr();
            if (tempOperand == null) return null;
            InferredType tempInner = infer(tempOperand, depth + 1);
            if (tempInner == null) return null;
            return InferredType.of(InferredType.Kind.POINTER, tempInner.getName() + "*");
        }
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
            if (op.equals("??") && leftType != null && rightType != null && isOptionalName(leftType.getName()))
            {
                // `opt ?? default`: the default replaces the empty case, so a
                // matching default unwraps the result to the plain value type.
                String unwrapped = stripOptional(leftType.getName());
                if (namesEqual(unwrapped, rightType.getName())) return kindOf(unwrapped);
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
        String declared = assignedTypeText(resolved);
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
        String declared = assignedTypeText(resolved);
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

    /**
     * Declared type of a variable for assignment checking, preserving the
     * Optional suffix: {@code int?} for {@code int? x}, plain text otherwise.
     */
    public static @Nullable String assignedTypeText(@NotNull PsiElement resolved)
    {
        String base = declaredTypeText(resolved);
        if (base == null) return null;
        if (resolved instanceof C3LocalDeclAfterType)
        {
            C3LocalDeclarationStmt stmt =
                PsiTreeUtil.getParentOfType(resolved, C3LocalDeclarationStmt.class);
            if (stmt != null && stmt.getOptionalType() != null
                && stmt.getOptionalType().getNode().findChildByType(C3Types.QUESTION) != null
                && !isOptionalName(base))
            {
                return base + "?";
            }
        }
        return base;
    }

    private static @Nullable InferredType inferCall(@NotNull C3CallExpr call, int depth)
    {
        if (call.getCallExprTail() == null || call.getCallExprTail().getCallInvocation() == null)
        {
            C3CallExprTail tail = call.getCallExprTail();
            if (tail != null && depth < MAX_DEPTH)
            {
                ASTNode tailNode = tail.getNode();
                boolean rethrow = tailNode.findChildByType(C3Types.BANG) != null;
                boolean force = !rethrow && tailNode.findChildByType(C3Types.BANGBANG) != null;
                if (rethrow || force)
                {
                    // `expr!` (rethrow) and `expr!!` (force unwrap) evaluate to
                    // the Optional's result type.
                    InferredType inner = infer(call.getExpr(), depth + 1);
                    if (inner == null) return null;
                    if (isOptionalName(inner.getName())) return kindOf(stripOptional(inner.getName()));
                    return inner;
                }
                // `expr~` builds an Optional excuse: the result type comes
                // from the context, so it stays unknown here.
                if (tailNode.findChildByType(C3Types.BIT_NOT) != null) return null;
            }
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
            if (resolved instanceof C3FuncDef funcDef) return cascadeOptional(call, returnTypeOf(funcDef), depth);
            if (resolved instanceof C3MacroDefinition macro) return cascadeOptional(call, returnTypeOf(macro), depth);
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
                if (resolved instanceof C3CallablePsiElement callable) return cascadeOptional(call, returnTypeOf(callable), depth);
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

    /**
     * Cascading: calling a function with an Optional argument only executes
     * the function when every Optional argument holds a result, so a plain
     * result type becomes Optional. An already-Optional result stays as is.
     */
    private static @Nullable InferredType cascadeOptional(
            @NotNull C3CallExpr call, @Nullable InferredType result, int depth)
    {
        if (result == null || isOptionalName(result.getName()) || depth >= MAX_DEPTH) return result;
        // `@catch`/`@ok` consume an Optional and return a plain value.
        C3Expr callee = call.getExpr();
        if (callee instanceof C3PathAtIdentExpr atExpr && atExpr.getPathAtIdent() != null)
        {
            String name = atExpr.getPathAtIdent().getText();
            if (name != null && (name.strip().equals("@catch") || name.strip().equals("@ok"))) return result;
        }
        C3CallExprTail tail = call.getCallExprTail();
        C3CallInvocation invocation = tail != null ? tail.getCallInvocation() : null;
        C3CallArgList callArgs = invocation != null ? invocation.getCallArgList() : null;
        C3ArgList args = callArgs != null ? callArgs.getArgList() : null;
        if (args == null) return result;
        for (C3Arg arg : args.getArgList())
        {
            C3Expr argExpr = arg.getExpr();
            if (argExpr == null) continue;
            InferredType argType = infer(argExpr, depth + 1);
            if (argType != null && isOptionalName(argType.getName()))
            {
                return kindOf(result.getName() + "?");
            }
        }
        return result;
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
