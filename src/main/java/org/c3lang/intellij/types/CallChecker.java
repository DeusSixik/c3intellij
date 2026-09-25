package org.c3lang.intellij.types;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import org.c3lang.intellij.C3Util;
import org.c3lang.intellij.index.InterfaceService;
import org.c3lang.intellij.psi.C3AccessIdent;
import org.c3lang.intellij.psi.C3Arg;
import org.c3lang.intellij.psi.C3ArgList;
import org.c3lang.intellij.psi.C3CallArgList;
import org.c3lang.intellij.psi.C3CallExpr;
import org.c3lang.intellij.psi.C3CallExprTail;
import org.c3lang.intellij.psi.C3CallInvocation;
import org.c3lang.intellij.psi.C3CallablePsiElement;
import org.c3lang.intellij.psi.C3Expr;
import org.c3lang.intellij.psi.C3FuncDef;
import org.c3lang.intellij.psi.C3MacroDefinition;
import org.c3lang.intellij.psi.C3MacroParams;
import org.c3lang.intellij.psi.C3MacroDefinition;
import org.c3lang.intellij.psi.C3NamedIdent;
import org.c3lang.intellij.psi.C3ParamDecl;
import org.c3lang.intellij.psi.C3Parameter;
import org.c3lang.intellij.psi.C3ParameterList;
import org.c3lang.intellij.psi.C3PathAtIdent;
import org.c3lang.intellij.psi.C3PathAtIdentExpr;
import org.c3lang.intellij.psi.C3PathIdent;
import org.c3lang.intellij.psi.C3PathIdentExpr;
import org.c3lang.intellij.psi.C3PsiElement;
import org.c3lang.intellij.psi.C3TrailingBlockParam;
import org.c3lang.intellij.psi.C3TypeExpr;
import org.c3lang.intellij.psi.C3Types;
import org.c3lang.intellij.psi.ModuleName;
import org.c3lang.intellij.psi.ParamType;
import org.c3lang.intellij.psi.reference.C3AtMacroReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Semantic checks for function and method calls: unknown named arguments,
 * named/positional ordering, arity (vaarg- and default-aware) and per-argument types.
 *
 * <p>Rules follow {@code docs/functions.md}: named arguments, defaults, vaargs and splats.
 * Anything unresolvable is skipped silently.
 */
public final class CallChecker
{
    private CallChecker()
    {
    }

    public static void checkCall(@NotNull C3CallExpr call, @NotNull AnnotationHolder holder)
    {
        Project project = call.getProject();
        if (DumbService.isDumb(project)) return;
        C3CallExprTail tail = call.getCallExprTail();
        if (tail == null || tail.getCallInvocation() == null) return;
        if (tail.getGenericParameters() != null) return;

        Callee callee = resolveCallee(call, tail);
        if (callee == null || callee.callable == null) return;

        Signature signature = buildSignature(callee.callable);
        List<ParamInfo> params = signature.params;
        int startIndex = 0;
        if (callee.ownerText != null && !callee.staticReceiver && !params.isEmpty()
            && InterfaceService.firstParameterMatchesOwner(signature.paramTypes, signature.parameterList, callee.ownerText))
        {
            // Dot-form method call: the receiver fills the first (self) parameter.
            startIndex = 1;
        }

        List<ArgInfo> args = buildArgs(tail.getCallInvocation());
        match(call, callee, signature, startIndex, args, holder);
    }

    // ------------------------------------------------------------------
    // Callee resolution
    // ------------------------------------------------------------------

    private static final class Callee
    {
        final @NotNull C3CallablePsiElement callable;
        final @Nullable String ownerText;
        final boolean staticReceiver;

        Callee(@NotNull C3CallablePsiElement callable, @Nullable String ownerText, boolean staticReceiver)
        {
            this.callable = callable;
            this.ownerText = ownerText;
            this.staticReceiver = staticReceiver;
        }
    }

    private static @Nullable Callee resolveCallee(@NotNull C3CallExpr call, @NotNull C3CallExprTail tail)
    {
        C3AccessIdent methodIdent = tail.getAccessIdent();
        if (methodIdent != null)
        {
            // Tail carries both the method and the invocation.
            C3CallablePsiElement callable = resolveCallable(methodIdent.getReference());
            if (callable == null) return null;
            return new Callee(callable, ownerOf(callable), isStaticReceiver(call.getExpr()));
        }
        C3Expr calleeExpr = call.getExpr();
        if (calleeExpr instanceof C3PathIdentExpr pathIdentExpr)
        {
            C3CallablePsiElement callable = resolveCallable(pathIdentExpr.getPathIdent().getReference());
            if (callable == null) return null;
            return new Callee(callable, ownerOf(callable), false);
        }
        if (calleeExpr instanceof C3PathAtIdentExpr pathAtIdentExpr)
        {
            // `@macro(args)` calls.
            C3CallablePsiElement callable = resolveAtCallable(pathAtIdentExpr.getPathAtIdent());
            if (callable == null) return null;
            return new Callee(callable, ownerOf(callable), false);
        }
        if (calleeExpr instanceof C3CallExpr inner
            && inner.getCallExprTail() != null
            && inner.getCallExprTail().getAccessIdent() != null
            && inner.getCallExprTail().getCallInvocation() == null)
        {
            // Split form: recv.method(args) or Type.method(args).
            C3CallablePsiElement callable = resolveCallable(inner.getCallExprTail().getAccessIdent().getReference());
            if (callable == null) return null;
            return new Callee(callable, ownerOf(callable), isStaticReceiver(inner.getExpr()));
        }
        return null;
    }

    private static @Nullable String ownerOf(@NotNull C3CallablePsiElement callable)
    {
        if (callable instanceof C3FuncDef funcDef) return InterfaceService.methodOwnerTypeName(funcDef);
        if (callable instanceof C3MacroDefinition macro) return InterfaceService.methodOwnerTypeName(macro);
        return null;
    }

    private static boolean isStaticReceiver(@Nullable C3Expr receiver)
    {
        return receiver instanceof C3TypeExpr;
    }

    private static @Nullable C3CallablePsiElement resolveCallable(@NotNull PsiReference reference)
    {
        PsiElement resolved;
        try
        {
            resolved = reference.resolve();
        }
        catch (Exception e)
        {
            return null;
        }
        return resolved instanceof C3CallablePsiElement callable ? callable : null;
    }

    private static @Nullable C3CallablePsiElement resolveAtCallable(@NotNull C3PathAtIdent atIdent)
    {
        for (PsiReference reference : atIdent.getReferences())
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
            if (resolved instanceof C3CallablePsiElement callable) return callable;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Parameters and arguments (callee signatures)
    // ------------------------------------------------------------------

    static final class ParamInfo
    {
        final @Nullable String name;
        final @Nullable String typeText;
        final boolean required;
        final boolean vaarg;
        final @Nullable String vaargElement;

        ParamInfo(@Nullable String name, @Nullable String typeText, boolean required, boolean vaarg, @Nullable String vaargElement)
        {
            this.name = name;
            this.typeText = typeText;
            this.required = required;
            this.vaarg = vaarg;
            this.vaargElement = vaargElement;
        }
    }

    static final class ArgInfo
    {
        final @NotNull C3Arg arg;
        final @Nullable String name;
        final boolean named;
        final boolean splat;
        final @Nullable C3Expr expr;
        final @NotNull PsiElement anchor;

        ArgInfo(@NotNull C3Arg arg, @Nullable String name, boolean named, boolean splat, @Nullable C3Expr expr, @NotNull PsiElement anchor)
        {
            this.arg = arg;
            this.name = name;
            this.named = named;
            this.splat = splat;
            this.expr = expr;
            this.anchor = anchor;
        }
    }

    static final class Signature
    {
        final @NotNull List<ParamInfo> params;
        final @NotNull List<ParamType> paramTypes;
        final @Nullable C3ParameterList parameterList;
        final boolean needsTrailingBlock;

        Signature(
                @NotNull List<ParamInfo> params,
                @NotNull List<ParamType> paramTypes,
                @Nullable C3ParameterList parameterList,
                boolean needsTrailingBlock)
        {
            this.params = params;
            this.paramTypes = paramTypes;
            this.parameterList = parameterList;
            this.needsTrailingBlock = needsTrailingBlock;
        }
    }

    static @NotNull Signature buildSignature(@NotNull C3CallablePsiElement callable)
    {
        if (callable instanceof C3FuncDef funcDef)
        {
            C3ParameterList list = funcDef.getFnParameterList().getParameterList();
            return new Signature(paramsFrom(list), funcDef.getParameterTypes(), list, false);
        }
        if (callable instanceof C3MacroDefinition macro && macro.getMacroParams() != null)
        {
            C3MacroParams macroParams = macro.getMacroParams();
            C3ParameterList list = macroParams.getParameterList();
            return new Signature(
                paramsFrom(list),
                macro.getParameterTypes(),
                list,
                macroParams.getTrailingBlockParam() != null);
        }
        return new Signature(List.of(), List.of(), null, false);
    }

    static @NotNull List<ParamInfo> paramsFrom(@Nullable C3ParameterList list)
    {
        List<ParamInfo> result = new ArrayList<>();
        if (list == null) return result;
        for (C3ParamDecl decl : list.getParamDeclList())
        {
            C3Parameter parameter = decl.getParameter();
            if (parameter == null) continue;
            String name = parameter.getNameIdent();
            if (name != null) name = name.replaceAll("^[#$@]+", "");
            boolean vaarg = parameter.getNode().findChildByType(C3Types.ELLIPSIS) != null;
            String typeText = parameter.getType() != null ? parameter.getType().getText() : null;
            boolean hasDefault = decl.getExpr() != null;
            result.add(new ParamInfo(
                name,
                typeText,
                !hasDefault && !vaarg,
                vaarg,
                vaarg ? (typeText != null ? typeText : "any") : null));
        }
        return result;
    }

    static @NotNull List<ArgInfo> buildArgs(@NotNull C3CallInvocation invocation)
    {
        List<ArgInfo> result = new ArrayList<>();
        C3CallArgList callArgList = invocation.getCallArgList();
        if (callArgList == null) return result;
        C3ArgList argList = callArgList.getArgList();
        if (argList == null) return result;
        for (C3Arg arg : argList.getArgList())
        {
            if (arg.getParamPath() != null)
            {
                result.add(new ArgInfo(arg, null, true, false, arg.getExpr(), arg));
                continue;
            }
            C3NamedIdent namedIdent = arg.getNamedIdent();
            if (namedIdent != null)
            {
                result.add(new ArgInfo(arg, namedIdent.getText(), true, false, arg.getExpr(), namedIdent));
                continue;
            }
            if (arg.getText().strip().startsWith("..."))
            {
                result.add(new ArgInfo(arg, null, false, true, arg.getExpr(), arg));
                continue;
            }
            C3Expr expr = arg.getExpr();
            result.add(new ArgInfo(arg, null, false, false, expr, expr != null ? expr : (PsiElement) arg));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Matching
    // ------------------------------------------------------------------

    private static void match(
            @NotNull C3CallExpr call,
            @NotNull Callee callee,
            @NotNull Signature signature,
            int startIndex,
            @NotNull List<ArgInfo> args,
            @NotNull AnnotationHolder holder)
    {
        Project project = call.getProject();
        ModuleName contextModule = ModuleName.from(call);
        List<ParamInfo> params = signature.params;

        C3CallExprTail callTail = call.getCallExprTail();
        boolean hasTrailingBlock = callTail != null && callTail.getCompoundStatement() != null;
        if (signature.needsTrailingBlock && !hasTrailingBlock && callTail != null && callTail.getCallInvocation() != null)
        {
            error(holder, callTail.getCallInvocation(), "Missing trailing block.");
        }
        if (!signature.needsTrailingBlock && hasTrailingBlock)
        {
            String calleeName = callee.callable.getName();
            error(holder, callTail.getCompoundStatement(),
                "'" + (calleeName != null ? calleeName : callee.callable.getText()) + "' takes no trailing block.");
        }

        int vaargIndex = -1;
        for (int i = startIndex; i < params.size(); i++)
        {
            if (params.get(i).vaarg)
            {
                vaargIndex = i;
                break;
            }
        }

        boolean[] filled = new boolean[params.size()];
        boolean namedSeen = false;
        boolean splatSeen = false;
        int positionalCount = 0;

        for (ArgInfo arg : args)
        {
            if (arg.splat)
            {
                splatSeen = true;
                continue;
            }
            if (arg.named)
            {
                namedSeen = true;
                if (arg.name == null) continue;
                int index = findParam(params, startIndex, arg.name);
                if (index < 0)
                {
                    error(holder, arg.anchor, "Unknown parameter '" + arg.name + "'." + suggestion(params, startIndex, arg.name));
                    continue;
                }
                if (filled[index])
                {
                    error(holder, arg.anchor, "Parameter '" + arg.name + "' is already set.");
                    continue;
                }
                filled[index] = true;
                checkArgType(project, contextModule, holder, arg, params.get(index), index == vaargIndex);
                continue;
            }
            if (namedSeen)
            {
                error(holder, arg.anchor, "Unnamed arguments may not follow named arguments.");
                continue;
            }
            ParamInfo slot = null;
            int slotIndex = -1;
            int seen = 0;
            for (int i = startIndex; i < params.size(); i++)
            {
                if (i == vaargIndex) continue;
                if (seen == positionalCount)
                {
                    slot = params.get(i);
                    slotIndex = i;
                    break;
                }
                seen++;
            }
            if (slot == null)
            {
                if (vaargIndex >= 0)
                {
                    checkArgType(project, contextModule, holder, arg, params.get(vaargIndex), true);
                }
                else if (!splatSeen)
                {
                    error(holder, arg.anchor, "Too many arguments.");
                }
            }
            else
            {
                filled[slotIndex] = true;
                checkArgType(project, contextModule, holder, arg, slot, false);
            }
            positionalCount++;
        }

        if (!splatSeen)
        {
            C3CallInvocation invocation = call.getCallExprTail().getCallInvocation();
            for (int i = startIndex; i < params.size(); i++)
            {
                ParamInfo param = params.get(i);
                if (i != vaargIndex && param.required && !filled[i] && param.name != null)
                {
                    error(holder, invocation, "Missing argument for parameter '" + param.name + "'.");
                }
            }
        }
    }

    private static int findParam(@NotNull List<ParamInfo> params, int startIndex, @NotNull String name)
    {
        String stripped = InterfaceService.stripParamSigil(name);
        for (int i = startIndex; i < params.size(); i++)
        {
            String candidate = params.get(i).name;
            if (candidate == null) continue;
            if (name.equals(candidate) || stripped.equals(InterfaceService.stripParamSigil(candidate))) return i;
        }
        return -1;
    }

    private static @NotNull String suggestion(@NotNull List<ParamInfo> params, int startIndex, @NotNull String name)
    {
        List<String> candidates = new ArrayList<>();
        for (int i = startIndex; i < params.size(); i++)
        {
            if (params.get(i).name != null) candidates.add(params.get(i).name);
        }
        if (candidates.isEmpty()) return "";
        String best = C3Util.INSTANCE.findBestMatch(name, candidates);
        if (best == null || best.equals(name)) return "";
        return " Did you mean '" + best + "'?";
    }

    private static void checkArgType(
            @NotNull Project project,
            @Nullable ModuleName contextModule,
            @NotNull AnnotationHolder holder,
            @NotNull ArgInfo arg,
            @NotNull ParamInfo param,
            boolean vaargElement)
    {
        if (arg.expr == null) return;
        String typeText = vaargElement ? param.vaargElement : param.typeText;
        if (typeText == null) return;
        InferredType inferred = TypeChecker.infer(arg.expr);
        if (inferred == null) return;
        String error = TypeChecker.argumentError(
            project,
            contextModule,
            param.name != null ? param.name : typeText,
            typeText,
            inferred);
        if (error != null) holder.newAnnotation(HighlightSeverity.ERROR, error).range(arg.expr).create();
    }

    private static void error(@NotNull AnnotationHolder holder, @NotNull PsiElement anchor, @NotNull String message)
    {
        holder.newAnnotation(HighlightSeverity.ERROR, message).range(anchor).create();
    }
}
