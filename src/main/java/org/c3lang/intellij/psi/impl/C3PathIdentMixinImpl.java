package org.c3lang.intellij.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.c3lang.intellij.completion.CompletionExtensionsKt;
import org.c3lang.intellij.index.NameIndexService;
import org.c3lang.intellij.index.StructService;
import org.c3lang.intellij.psi.*;
import org.c3lang.intellij.psi.reference.C3ReferenceBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public abstract class C3PathIdentMixinImpl extends C3PsiNamedElementImpl implements C3PathIdent
{
	public C3PathIdentMixinImpl(@NotNull ASTNode node)
	{
		super(node);
	}

	@Override
	public @Nullable String getName()
	{
		return getNameIdent();
	}

	@Override
	public @Nullable PsiElement setName(@NotNull String name)
	{
		LeafPsiElement ident = getNameIdentElement();
		if (ident != null) ident.replaceWithText(name);
		return this;
	}

	@Override
	public @Nullable PsiElement getNameIdentifier()
	{
		return getNameIdentElement();
	}

	@Override
	public int getTextOffset()
	{
		LeafPsiElement ident = getNameIdentElement();
		return ident != null ? ident.getTextOffset() : super.getTextOffset();
	}

	@Override
	public @Nullable String getNameIdent()
	{
		LeafPsiElement ident = getNameIdentElement();
		return ident != null ? ident.getText() : null;
	}

	@Override
	public @Nullable LeafPsiElement getNameIdentElement()
	{
		PsiElement last = getLastChild();
		return last instanceof LeafPsiElement ? (LeafPsiElement) last : null;
	}

	@Override
	public @Nullable FullyQualifiedName findTypeName()
	{
		List<C3LocalDeclAfterType> decls = findLocalDeclAfterType();
		if (decls.size() == 1)
		{
			FullyQualifiedName fqn = decls.getFirst().findTypeName();
			if (fqn != null) return fqn;
		}

		String myName = getNameIdent();
		if (myName == null) return null;

		C3FuncDef funcDef = null;
		C3FuncDefinition funcDefinition = PsiTreeUtil.getParentOfType(this, C3FuncDefinition.class);
		if (funcDefinition != null)
		{
			funcDef = funcDefinition.getFuncDef();
		}
		else
		{
			funcDef = PsiTreeUtil.getParentOfType(this, C3FuncDef.class);
		}

		C3MacroDefinition macroDefinition = PsiTreeUtil.getParentOfType(this, C3MacroDefinition.class);

		if (funcDef != null)
		{
			if ("this".equals(myName) || "self".equals(myName))
			{
				C3Type type = funcDef.getFuncHeader().getFuncName().getType();
				if (type != null)
				{
					FullyQualifiedName fqn = resolveBaseTypeFqn(type, funcDef);
					if (fqn != null) return fqn;
				}
				ShortType methodType = funcDef.getType();
				if (methodType != null)
				{
					return new FullyQualifiedName(funcDef.getModuleName(), methodType.getValue());
				}
			}

			C3ParameterList parameterList = funcDef.getFnParameterList().getParameterList();
			if (parameterList != null)
			{
				for (C3ParamDecl paramDecl : parameterList.getParamDeclList())
				{
					C3Parameter param = paramDecl.getParameter();
					boolean matches = myName.equals(param.getNameIdent()) || myName.equals(param.getName());
					if (!matches)
					{
						for (ASTNode node : param.getNode().getChildren(null))
						{
							if (node.getElementType() == C3Types.IDENT && myName.equals(node.getText()))
							{
								matches = true;
								break;
							}
						}
					}
					if (matches)
					{
						C3Type type = param.getType();
						if (type != null)
						{
							return resolveBaseTypeFqn(type, funcDef);
						}
					}
				}
			}
		}

		if (macroDefinition != null && macroDefinition.getMacroParams().getParameterList() != null)
		{
			for (C3ParamDecl paramDecl : macroDefinition.getMacroParams().getParameterList().getParamDeclList())
			{
				C3Parameter macroParam = paramDecl.getParameter();
				if (macroParam == null) continue;
				if (myName.equals(macroParam.getNameIdent()) || myName.equals(macroParam.getName()))
				{
					C3Type type = macroParam.getType();
					if (type != null) return macroParamTypeFqn(type, macroDefinition);
				}
			}
		}

		C3CompoundStatement compoundStatement =
			PsiTreeUtil.getParentOfType(this, C3CompoundStatement.class);
		if (compoundStatement != null)
		{
			Collection<C3VarDecl> varDecls =
				PsiTreeUtil.collectElementsOfType(compoundStatement, C3VarDecl.class);
			for (C3VarDecl v : varDecls)
			{
				if (v.getTextOffset() < getTextOffset())
				{
					ASTNode identNode = v.getNode().findChildByType(C3Types.IDENT);
					if (identNode != null && myName.equals(identNode.getText()))
					{
						if (v.getExpr() instanceof C3CompoundInitExpr initExpr)
						{
							if (funcDef != null)
							{
								return resolveBaseTypeFqn(initExpr.getType(), funcDef);
							}
							C3ModuleDefinition initModule = initExpr.getModuleDefinition();
							if (initModule == null) return null;
							List<FullyQualifiedName> res = initModule.resolve(initExpr.getType());
							if (!res.isEmpty()) return res.get(0);
						}
					}
				}
			}
		}

		return null;
	}

	private static @Nullable FullyQualifiedName resolveBaseTypeFqn(@NotNull C3Type type, @NotNull C3FuncDef funcDef)
	{
		C3ModuleDefinition funcModule = funcDef.getModuleDefinition();
		ModuleName fallback = funcDef.getModuleName();
		return resolveBaseTypeFqn(type, funcModule, fallback);
	}

	private static @Nullable FullyQualifiedName macroParamTypeFqn(@NotNull C3Type type, @NotNull C3MacroDefinition macro)
	{
		return resolveBaseTypeFqn(type, macro.getModuleDefinition(), ModuleName.from(macro));
	}

	private static @Nullable FullyQualifiedName resolveBaseTypeFqn(
			@NotNull C3Type type,
			@Nullable C3ModuleDefinition moduleDefinition,
			@Nullable ModuleName fallbackModule)
	{
		C3BaseType baseType = type.getBaseType();
		if (baseType.isPrimitiveType()) return null;
		PsiReference ref = baseType.getReference();
		if (ref != null)
		{
			PsiElement resolved = ref.resolve();
			if (resolved instanceof C3TypeName tn)
			{
				return tn.getFqName();
			}
		}

		String nameIdent = baseType.getNameIdent();
		if (nameIdent == null)
		{
			nameIdent = baseType.getText();
			int idx = nameIdent.indexOf('<');
			if (idx > 0) nameIdent = nameIdent.substring(0, idx).trim();
			idx = nameIdent.indexOf('(');
			if (idx > 0) nameIdent = nameIdent.substring(0, idx).trim();
		}

		C3Path path = baseType.getPath();
		if (path != null)
		{
			String pathText = path.getText();
			if (pathText.endsWith("::")) pathText = pathText.substring(0, pathText.length() - 2);
			return new FullyQualifiedName(new ModuleName(pathText), nameIdent);
		}
		if (moduleDefinition != null)
		{
			List<FullyQualifiedName> resolved = moduleDefinition.resolve(type);
			if (!resolved.isEmpty()) return resolved.get(0);
		}
		return new FullyQualifiedName(fallbackModule, nameIdent);
	}

	@Override
	public @NotNull List<C3LocalDeclAfterType> findLocalDeclAfterType()
	{
		C3CompoundStatement scope =
			PsiTreeUtil.getParentOfType(this, C3CompoundStatement.class);
		while (scope != null)
		{
			// Nearest declaration visible from here: an inner block shadows
			// the outer ones, and within one block the last declaration
			// before the use wins.
			C3LocalDeclAfterType best = null;
			for (C3LocalDeclAfterType decl : PsiTreeUtil.findChildrenOfType(scope, C3LocalDeclAfterType.class))
			{
				if (!PsiTreeUtil.isAncestor(scope, decl, false)) continue;
				if (!isVisibleFrom(decl)) continue;
				if (decl.getTextOffset() < getTextOffset()
					&& decl.getNameIdent() != null
					&& decl.getNameIdent().equals(getNameIdent())
					&& (best == null || decl.getTextOffset() > best.getTextOffset()))
				{
					best = decl;
				}
			}
			if (best != null) return Collections.singletonList(best);
			scope = PsiTreeUtil.getParentOfType(scope, C3CompoundStatement.class);
		}
		return Collections.emptyList();
	}

	/**
	 * A declaration is visible from this use when no nested block boundary
	 * sits between them, unless the use itself is inside that nested block.
	 * In other words: the declaration's innermost owning block must also own
	 * (or be) the use, or own an ancestor of the use.
	 */
	private boolean isVisibleFrom(@NotNull C3LocalDeclAfterType decl)
	{
		C3CompoundStatement declScope =
			PsiTreeUtil.getParentOfType(decl, C3CompoundStatement.class);
		if (declScope == null) return true;
		PsiElement current = this;
		while (current != null && current != declScope)
		{
			current = current.getParent();
		}
		if (current == null) return false;
		return decl.getTextOffset() < getTextOffset();
	}


	private boolean hasLocalDeclBeforeUse()
	{
		return !new C3LocalDeclAfterTypeReference(this).multiResolve().isEmpty();
	}

	private boolean hasParameterBeforeUse()
	{
		return !new C3ParameterReference(this).multiResolve().isEmpty();
	}

	private boolean isStructMemberAccess()
	{
		return CompletionExtensionsKt.getRootType(this) != null
			&& PsiTreeUtil.getParentOfType(this, C3PathNameProvider.class) != null;
	}

	private boolean isCallablePosition()
	{
		return isCallCallee() || isReflectOperand() || isAddressOfOperand();
	}

	private boolean isCallCallee()
	{
		C3PathIdentExpr expr = PsiTreeUtil.getParentOfType(this, C3PathIdentExpr.class);
		if (expr == null) return false;

		C3CallExpr call = PsiTreeUtil.getParentOfType(expr, C3CallExpr.class);
		return call != null && call.getExpr() == expr;
	}

	private boolean isReflectOperand()
	{
		C3PathIdentExpr expr = PsiTreeUtil.getParentOfType(this, C3PathIdentExpr.class);
		if (expr == null) return false;

		C3CtAnalyzeExpr analyzeExpr = PsiTreeUtil.getParentOfType(expr, C3CtAnalyzeExpr.class);
		if (analyzeExpr == null) return false;

		return analyzeExpr.getCtAnalyze().getText().equals("$reflect")
			&& analyzeExpr.getGroupedExpr() != null
			&& PsiTreeUtil.isAncestor(analyzeExpr.getGroupedExpr(), expr, false);
	}

	private boolean isAddressOfOperand()
	{
		C3PathIdentExpr expr = PsiTreeUtil.getParentOfType(this, C3PathIdentExpr.class);
		if (expr == null) return false;

		C3UnaryExpr unaryExpr = PsiTreeUtil.getParentOfType(expr, C3UnaryExpr.class);
		if (unaryExpr == null) return false;

		return unaryExpr.getExpr() == expr && unaryExpr.getUnaryOp().getText().equals("&");
	}

	@Override
	public @NotNull PsiReference getReference()
	{
		if (hasLocalDeclBeforeUse()) return new C3LocalDeclAfterTypeReference(this);
		if (hasParameterBeforeUse()) return new C3ParameterReference(this);
		if (isCallablePosition()) return new C3FuncNameReference(this);
		if (isStructMemberAccess()) return new C3StructMemberReference(this);
		return new C3LocalDeclAfterTypeReference(this);
	}

	private static class C3LocalDeclAfterTypeReference extends C3ReferenceBase<C3PathIdent>
	{
		C3LocalDeclAfterTypeReference(@NotNull C3PathIdent element)
		{
			super(element);
		}

		@Override
		public @NotNull Collection<C3PsiElement> multiResolve()
		{
			if (!(myElement instanceof C3PathIdentMixinImpl mixin)) return Collections.emptyList();
			return new ArrayList<>(mixin.findLocalDeclAfterType());
		}
	}

	private static class C3ParameterReference extends C3ReferenceBase<C3PathIdent>
	{
		C3ParameterReference(@NotNull C3PathIdent element)
		{
			super(element);
		}

		@Override
		public @NotNull Collection<C3PsiElement> multiResolve()
		{
			C3FuncDefinition funcDef =
				PsiTreeUtil.getParentOfType(myElement, C3FuncDefinition.class);
			if (funcDef == null) return Collections.emptyList();

			Collection<C3Parameter> params =
				PsiTreeUtil.collectElementsOfType(funcDef, C3Parameter.class);
			for (C3Parameter param : params)
			{
				if (param.getNameIdent() != null && param.getNameIdent().equals(myElement.getNameIdent()))
				{
					return Collections.singleton(param);
				}
			}
			return Collections.emptyList();
		}
	}

	private static class C3FuncNameReference extends C3ReferenceBase<C3PathIdent>
	{
		C3FuncNameReference(@NotNull C3PathIdent element)
		{
			super(element);
		}

		@Override
		public @NotNull Collection<C3PsiElement> multiResolve()
		{
			C3ModuleDefinition moduleDefinition = myElement.getModuleDefinition();
			List<C3PsiElement> result = new ArrayList<>();
			if (moduleDefinition == null) return result;
			for (C3FullyQualifiedNamePsiElement el :
				NameIndexService.INSTANCE.findByNameEndsWith(myElement.getText(), myElement.getProject()))
			{
				if (el instanceof C3CallablePsiElement
					&& el.getFqName().getName().equals(myElement.getNameIdent())
					&& moduleDefinition.containsImportOrSameModule(el))
				{
					result.add(el);
				}
			}
			return result;
		}

		@Override
		public @NotNull TextRange getRangeInElement()
		{
			C3Path path = myElement.getPath();
			return TextRange.create(path != null ? path.getTextLength() : 0, myElement.getTextLength());
		}
	}

	private static class C3StructMemberReference extends C3ReferenceBase<C3PathIdent>
	{
		C3StructMemberReference(@NotNull C3PathIdent element)
		{
			super(element);
		}

		@Override
		public @NotNull Collection<C3PsiElement> multiResolve()
		{
			FullyQualifiedName rootType = CompletionExtensionsKt.getRootType(myElement);
			if (rootType == null) return Collections.emptyList();

			C3Arg parentArg = PsiTreeUtil.getParentOfType(myElement, C3Arg.class);
			C3PathNameProvider pathNameProvider =
				PsiTreeUtil.getParentOfType(myElement, C3PathNameProvider.class);
			if (pathNameProvider == null) return Collections.emptyList();
			List<String> path = pathNameProvider.findPathName(false);

			// Walk up through all C3PathNameProvider ancestors of parentArg
			List<String> fieldNames = new ArrayList<>();
			C3PathNameProvider currentProvider = parentArg != null
				? PsiTreeUtil.getParentOfType(parentArg, C3PathNameProvider.class)
				: null;
			while (currentProvider != null)
			{
				fieldNames.addAll(currentProvider.findPathName(false));
				currentProvider = PsiTreeUtil.getParentOfType(currentProvider, C3PathNameProvider.class);
			}
			Collections.reverse(fieldNames);

			List<String> paths = new ArrayList<>(fieldNames);
			paths.addAll(path);
			paths.add(myElement.getText());

			return new ArrayList<>(
				StructService.INSTANCE.getStructMemberDeclaration(rootType, paths, myElement.getProject()));
		}
	}
}
