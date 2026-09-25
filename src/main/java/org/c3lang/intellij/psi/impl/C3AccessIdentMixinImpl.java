package org.c3lang.intellij.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.util.PsiTreeUtil;
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

public abstract class C3AccessIdentMixinImpl extends C3PsiNamedElementImpl implements C3AccessIdent
{
	public C3AccessIdentMixinImpl(@NotNull ASTNode node)
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
	public @Nullable String getNameIdent()
	{
		LeafPsiElement ident = getNameIdentElement();
		return ident != null ? ident.getText() : null;
	}

	@Override
	public @Nullable LeafPsiElement getNameIdentElement()
	{
		PsiElement first = getFirstChild();
		return first instanceof LeafPsiElement ? (LeafPsiElement) first : null;
	}

	@Override
	public int getTextOffset()
	{
		LeafPsiElement ident = getNameIdentElement();
		return ident != null ? ident.getTextOffset() : super.getTextOffset();
	}

	@Override
	public @NotNull TextRange getTextRange()
	{
		LeafPsiElement ident = getNameIdentElement();
		return ident != null ? ident.getTextRange() : super.getTextRange();
	}

	@Override
	public @NotNull PsiReference getReference()
	{
		return new StructMemberReference(this);
	}

	@Override
	public @Nullable FullyQualifiedName findTypeName()
	{
		C3PsiElement resolved = new StructMemberReference(this).resolve();
		if (!(resolved instanceof C3FullyQualifiedTypeNameProvider)) return null;
		return ((C3FullyQualifiedTypeNameProvider) resolved).findTypeName();
	}

	private static class StructMemberReference extends C3ReferenceBase<C3AccessIdent>
	{
		StructMemberReference(@NotNull C3AccessIdent element)
		{
			super(element);
		}

		@Override
		public @NotNull Collection<C3PsiElement> multiResolve()
		{
			C3CallExpr call = findAccessCallExpr();
			if (call == null) return Collections.emptyList();

			AccessIdentSequence seq = getAccessIdentSequence(call);
			if (seq == null)
			{
				return isInvocationCallee()
					? findMethodsMatchingAccessName()
					: findFieldsOrMethodsMatchingAccessName();
			}

			String query = seq.rootType.getFullName();
			FullyQualifiedName currentType = seq.rootType;
			List<C3StructMemberDeclaration> structMembers = Collections.emptyList();

			for (int i = 0; i < seq.idents.size(); i++)
			{
				String ident = seq.idents.get(i);
				boolean last = i == seq.idents.size() - 1;
				structMembers = StructService.INSTANCE.getStructMembers(query + "." + ident, myElement.getProject());
				C3StructMemberDeclaration member = structMembers.size() == 1 ? structMembers.get(0) : null;
				if (member != null)
				{
					FullyQualifiedName nextType = member.getStructPathType();
					if (nextType != null)
					{
						currentType = nextType;
						query = nextType.getFullName();
						continue;
					}
					if (last && !isInvocationCallee())
					{
						// Leaf member (e.g. an int field): it is the answer.
						return new ArrayList<>(structMembers);
					}
				}
				if (last)
				{
					return lastIdentResults(currentType, ident, structMembers);
				}
				// An intermediate segment does not resolve: deeper segments cannot either.
				return Collections.emptyList();
			}

			return !structMembers.isEmpty()
				? new ArrayList<>(structMembers)
				: Collections.emptyList();
		}

		private @NotNull Collection<C3PsiElement> lastIdentResults(
				@NotNull FullyQualifiedName currentType,
				@NotNull String ident,
				@NotNull List<C3StructMemberDeclaration> structMembers)
		{
			if (isInvocationCallee())
			{
				Collection<C3PsiElement> methods = findMethodsForCurrentType(currentType, ident);
				if (!methods.isEmpty()) return methods;
				// A field holding a function pointer invoked as `s.cb()`.
				if (!structMembers.isEmpty()) return new ArrayList<>(structMembers);
				return Collections.emptyList();
			}
			if (!structMembers.isEmpty()) return new ArrayList<>(structMembers);
			// A method referenced as a value, e.g. `&s.method`.
			return findMethodsForCurrentType(currentType, ident);
		}

		private @NotNull Collection<C3PsiElement> findMethodsForCurrentType(
				@NotNull FullyQualifiedName currentType,
				@NotNull String ident)
		{
			for (C3FuncDef funcDef : PsiTreeUtil.findChildrenOfType(myElement.getContainingFile(), C3FuncDef.class))
			{
				if (funcDef.getType() != null && currentType.getSuffixName().equals(funcDef.getType().getValue()))
				{
					if (funcDef.getFqName().getName().endsWith("." + ident))
					{
						return List.of(funcDef);
					}
				}
			}

			Collection<C3CallablePsiElement> methods =
				NameIndexService.INSTANCE.findMethodsForType(currentType, ident, myElement.getProject());
			if (!methods.isEmpty())
			{
				return new ArrayList<>(methods);
			}

			if (currentType.getModule() != null)
			{
				C3Module mod = C3ImportPathMixinImpl.findModuleDirectly(currentType.getModule().getValue(), myElement.getProject());
				if (mod != null)
				{
					for (C3FuncDef funcDef : PsiTreeUtil.findChildrenOfType(mod.getContainingFile(), C3FuncDef.class))
					{
						if (funcDef.getType() != null && currentType.getSuffixName().equals(funcDef.getType().getValue()))
						{
							if (funcDef.getFqName().getName().endsWith("." + ident))
							{
								return List.of(funcDef);
							}
						}
					}
				}
			}
			return Collections.emptyList();
		}

		private @NotNull Collection<C3PsiElement> findMethodsMatchingAccessName()
		{
			String name = myElement.getNameIdent();
			if (name == null) return Collections.emptyList();

			List<C3PsiElement> result = new ArrayList<>();
			for (C3FuncDef funcDef : PsiTreeUtil.findChildrenOfType(myElement.getContainingFile(), C3FuncDef.class))
			{
				if (funcDef.getFqName().getName().endsWith("." + name) && !result.contains(funcDef))
				{
					result.add(funcDef);
				}
			}

			C3ModuleDefinition moduleDefinition =
				PsiTreeUtil.getParentOfType(myElement, C3ModuleDefinition.class);

			for (C3CallablePsiElement method : NameIndexService.INSTANCE.findMethodsByName(name, myElement.getProject()))
			{
				if (moduleDefinition == null || moduleDefinition.containsImportOrSameModule(method))
				{
					if (!result.contains(method))
					{
						result.add(method);
					}
				}
			}

			return result;
		}

		private @NotNull Collection<C3PsiElement> findFieldsOrMethodsMatchingAccessName()
		{
			String name = myElement.getNameIdent();
			if (name == null) return Collections.emptyList();

			List<C3StructMemberDeclaration> fields =
				StructService.INSTANCE.findStructMembersByName(name, myElement.getProject());
			if (!fields.isEmpty()) return new ArrayList<>(fields);

			return findMethodsMatchingAccessName();
		}

		private boolean isInvocationCallee()
		{
			C3CallExpr accessExpr = findAccessCallExpr();
			if (accessExpr == null) return false;

			PsiElement parent = accessExpr.getParent();
			if (!(parent instanceof C3CallExpr invocationExpr)) return false;

			return invocationExpr.getExpr() == accessExpr
				&& invocationExpr.getCallExprTail().getCallInvocation() != null;
		}

		private @Nullable C3CallExpr findAccessCallExpr()
		{
			PsiElement current = myElement.getParent();
			while (current != null)
			{
				if (current instanceof C3CallExpr callExpr
					&& callExpr.getCallExprTail().getAccessIdent() == myElement)
				{
					return callExpr;
				}
				current = current.getParent();
			}
			return null;
		}

		@Override
		public @NotNull TextRange getRangeInElement()
		{
			return super.getRangeInElement();
		}

		private static @Nullable AccessIdentSequence getAccessIdentSequence(@NotNull C3CallExpr callExpr)
		{
			List<C3PsiElement> accessSequence = new ArrayList<>();
			C3PsiElement current = callExpr;
			while (true)
			{
				accessSequence.add(current);
				if (current instanceof C3ExprStmt)
				{
					current = ((C3ExprStmt)current).getExpr();
					continue;
				}
				if (current instanceof C3CallExpr)
				{
					current = ((C3CallExpr) current).getExpr();
					continue;
				}
				break;
			}

			C3PsiElement last = accessSequence.removeLast();
			if (!(last instanceof C3PathIdentExpr)) return null;

			C3PathIdentExpr rootExpr = (C3PathIdentExpr) last;
			FullyQualifiedName rootType = rootExpr.getPathIdent().findTypeName();
			if (rootType == null) return null;

			List<String> idents = new ArrayList<>();
			for (C3PsiElement elem : accessSequence)
			{
				if (elem instanceof C3CallExpr ce)
				{
					C3CallExprTail tail = ce.getCallExprTail();
					if (tail != null && tail.getAccessIdent() != null)
					{
						String identName = tail.getAccessIdent().getNameIdent();
						if (identName != null)
						{
							idents.add(identName);
							continue;
						}
					}
					String text = elem.getText();
					String[] parts = text.split("\\.");
					idents.add(parts[parts.length - 1]);
				}
			}
			Collections.reverse(idents);

			return new AccessIdentSequence(rootType, idents);
		}
	}

	private static final class AccessIdentSequence
	{
		final FullyQualifiedName rootType;
		final List<String> idents;

		AccessIdentSequence(@NotNull FullyQualifiedName rootType, @NotNull List<String> idents)
		{
			this.rootType = rootType;
			this.idents = idents;
		}
	}
}
