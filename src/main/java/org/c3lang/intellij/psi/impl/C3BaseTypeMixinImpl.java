package org.c3lang.intellij.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.c3lang.intellij.index.NameIndexService;
import org.c3lang.intellij.psi.*;
import org.c3lang.intellij.psi.reference.C3ReferenceBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public abstract class C3BaseTypeMixinImpl extends C3PsiNamedElementImpl implements C3BaseType
{
	public C3BaseTypeMixinImpl(@NotNull ASTNode node)
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
		PsiElement ident = getNameIdentifier();
		return ident != null ? ident.getTextOffset() : getNode().getStartOffset();
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
		ASTNode node = getNode().findChildByType(C3Types.TYPE_IDENT);
		if (node == null)
		{
			node = getNode().findChildByType(C3Types.CT_TYPE_IDENT);
		}
		return node != null && node.getPsi() instanceof LeafPsiElement ? (LeafPsiElement) node.getPsi() : null;
	}

	@Override
	public @Nullable PsiReference getReference()
	{
		if (isPrimitiveType() || getNameIdentElement() == null) return null;
		return new C3TypeNameReference(this);
	}

	private static class C3TypeNameReference extends C3ReferenceBase<C3BaseType>
	{
		C3TypeNameReference(@NotNull C3BaseType element)
		{
			super(element);
		}

		@Override
		public @NotNull Collection<C3PsiElement> multiResolve()
		{
			C3ModuleDefinition importProvider = myElement.getModuleDefinition();
			List<C3PsiElement> result = new ArrayList<>();
			String nameIdent = myElement.getNameIdent();
			if (nameIdent == null) return result;

			boolean searchCurrentFile = myElement.getPath() == null
				|| (importProvider.getModuleName() != null
					&& myElement.getPath().getText().equals(importProvider.getModuleName().getValue() + "::"));
			if (searchCurrentFile)
			{
				for (C3TypeName typeName : PsiTreeUtil.findChildrenOfType(myElement.getContainingFile(), C3TypeName.class))
				{
					if (nameIdent.equals(typeName.getNameIdent()) && !result.contains(typeName))
					{
						result.add(0, typeName);
					}
				}
			}

			for (C3FullyQualifiedNamePsiElement el :
				NameIndexService.INSTANCE.findType(myElement, myElement.getProject()))
			{
				boolean matches = myElement.getPath() != null || importProvider.containsImportOrSameModule(el);
				if (el instanceof C3TypeName && matches && !result.contains(el))
				{
					if (importProvider.isSameModule(el.getModuleName()))
					{
						result.add(0, el);
					}
					else
					{
						result.add(el);
					}
				}
			}

			if (result.isEmpty())
			{
				List<String> modulesToSearch = new ArrayList<>();
				for (ModuleName mn : importProvider.getImports())
				{
					modulesToSearch.add(mn.getValue());
				}
				if (!modulesToSearch.contains("std::core"))
				{
					modulesToSearch.add("std::core");
				}

				for (String modName : modulesToSearch)
				{
					C3Module mod = C3ImportPathMixinImpl.findModuleDirectly(modName, myElement.getProject());
					if (mod != null)
					{
						for (C3TypeName typeName : PsiTreeUtil.findChildrenOfType(mod.getContainingFile(), C3TypeName.class))
						{
							if (nameIdent.equals(typeName.getNameIdent()) && !result.contains(typeName))
							{
								result.add(typeName);
							}
						}
					}
				}
			}

			if (result.isEmpty())
			{
				C3ModuleSection moduleSection = com.intellij.psi.util.PsiTreeUtil.getParentOfType(myElement, C3ModuleSection.class);
				if (moduleSection != null && moduleSection.getModule().getGenericDecl() != null)
				{
					C3GenericDecl genericDecl = moduleSection.getModule().getGenericDecl();
					if (genericDecl.getModuleParams() != null)
					{
						for (C3ModuleParam param : genericDecl.getModuleParams().getModuleParamList())
						{
							if (nameIdent.equals(param.getText()))
							{
								result.add(param);
							}
						}
					}
				}
			}

			return result;
		}

		@Override
		public @NotNull TextRange getRangeInElement()
		{
			LeafPsiElement ident = myElement.getNameIdentElement();
			if (ident != null)
			{
				int start = ident.getTextRange().getStartOffset() - myElement.getTextRange().getStartOffset();
				return new TextRange(start, start + ident.getTextLength());
			}
			return super.getRangeInElement();
		}
	}
}
