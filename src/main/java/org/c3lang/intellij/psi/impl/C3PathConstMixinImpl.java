package org.c3lang.intellij.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import org.c3lang.intellij.index.NameIndexService;
import org.c3lang.intellij.psi.*;
import org.c3lang.intellij.psi.reference.C3ReferenceBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

public abstract class C3PathConstMixinImpl extends C3PsiNamedElementImpl implements C3PathConst
{
	public C3PathConstMixinImpl(@NotNull ASTNode node)
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
		PsiElement first = getFirstChild();
		if (first != null && first.getNode().getElementType() == C3Types.CONST_IDENT)
		{
			return (LeafPsiElement) first;
		}
		return null;
	}

	@Override
	public @Nullable PsiReference getReference()
	{
		return new PsiMultiReference(
			new PsiReference[]{
				new C3ConstDeclarationStmtReference(this),
				new C3FaultDeclarationReference(this),
			},
			this
		);
	}

	private static class C3ConstDeclarationStmtReference extends C3ReferenceBase<C3PathConst>
	{
		C3ConstDeclarationStmtReference(@NotNull C3PathConst element)
		{
			super(element);
		}

		@Override
		public @NotNull Collection<C3PsiElement> multiResolve()
		{
			C3ModuleDefinition moduleDefinition = myElement.getModuleDefinition();
			java.util.List<C3PsiElement> result = new java.util.ArrayList<>();
			if (moduleDefinition == null) return result;
			for (C3FullyQualifiedNamePsiElement el :
				NameIndexService.INSTANCE.findByNameEndsWith(myElement.getText(), myElement.getProject()))
			{
				if (el instanceof C3ConstDeclarationStmt
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

	private static class C3FaultDeclarationReference extends C3ReferenceBase<C3PathConst>
	{
		C3FaultDeclarationReference(@NotNull C3PathConst element)
		{
			super(element);
		}

		@Override
		public @NotNull Collection<C3PsiElement> multiResolve()
		{
			C3ModuleDefinition moduleDefinition = myElement.getModuleDefinition();
			java.util.List<C3PsiElement> result = new java.util.ArrayList<>();
			if (moduleDefinition == null) return result;
			for (C3FullyQualifiedNamePsiElement el :
				NameIndexService.INSTANCE.findByNameEndsWith(myElement.getText(), myElement.getProject()))
			{
				if (el instanceof C3FaultDefinition
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
			return TextRange.create(path != null ? path.getTextLength() : 0, myElement.getTextLength() + 1);
		}
	}
}
