package org.c3lang.intellij.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReference;
import com.intellij.psi.stubs.StubIndex;
import com.intellij.psi.util.PsiTreeUtil;
import org.c3lang.intellij.index.ModuleIndex;
import org.c3lang.intellij.project.C3ProjectService;
import org.c3lang.intellij.psi.*;
import org.c3lang.intellij.psi.reference.C3ReferenceBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public abstract class C3ImportPathMixinImpl extends C3PsiElementImpl implements C3ImportPath
{
	public C3ImportPathMixinImpl(@NotNull ASTNode node)
	{
		super(node);
	}

	@Override
	public boolean endsWith(@NotNull C3Path path)
	{
		String pathText = path.getText();
		String stripped = pathText.endsWith("::") ? pathText.substring(0, pathText.length() - 2) : pathText;
		return getText().endsWith(stripped);
	}

	@Override
	public int getTextOffset()
	{
		return getFirstChild().getTextOffset();
	}

	@Override
	public @NotNull PsiReference getReference()
	{
		return new C3ImportPathReference(this);
	}

	@Override
	public @Nullable ModuleName getModuleName()
	{
		return new ModuleName(getText());
	}

	public static @Nullable C3Module findModuleDirectly(@NotNull String moduleName, @NotNull Project project)
	{
		PsiManager psiManager = PsiManager.getInstance(project);
		LocalFileSystem lfs = LocalFileSystem.getInstance();
		// This helper runs from reference resolution, which can happen while
		// building stubs on an indexing thread: use only already-known stdlib
		// paths (no compiler detection, no settings writes) and never trigger
		// a synchronous VFS refresh in dumb mode.
		boolean dumb = com.intellij.openapi.project.DumbService.isDumb(project);

		String relativePath = moduleName.replace("::", "/");
		List<String> candidateRelPaths = new ArrayList<>();
		candidateRelPaths.add(relativePath + ".c3");
		candidateRelPaths.add(relativePath + ".c3i");
		if (relativePath.startsWith("std/"))
		{
			candidateRelPaths.add(relativePath.substring(4) + ".c3");
			candidateRelPaths.add(relativePath.substring(4) + ".c3i");
		}

		for (String stdlibPath : C3ProjectService.getInstance(project).getKnownStdlibPaths())
		{
			for (String relPath : candidateRelPaths)
			{
				String fullPath = stdlibPath + "/" + relPath;
				VirtualFile vf = lfs.findFileByPath(fullPath);
				if (vf == null && !dumb) vf = lfs.refreshAndFindFileByPath(fullPath);
				if (vf != null && vf.isValid())
				{
					PsiFile psi = psiManager.findFile(vf);
					if (psi instanceof C3File)
					{
						for (C3Module mod : PsiTreeUtil.findChildrenOfType(psi, C3Module.class))
						{
							ModuleName mn = mod.getModuleName();
							if (mn != null && moduleName.equals(mn.getValue()))
							{
								return mod;
							}
						}
					}
				}
			}
		}

		for (String stdlibPath : C3ProjectService.getInstance(project).getKnownStdlibPaths())
		{
			VirtualFile root = lfs.findFileByPath(stdlibPath);
			if (root == null && !dumb) root = lfs.refreshAndFindFileByPath(stdlibPath);
			if (root != null && root.isValid())
			{
				C3Module match = findModuleInVirtualFile(root, moduleName, psiManager);
				if (match != null) return match;
			}
		}

		return null;
	}

	private static @Nullable C3Module findModuleInVirtualFile(@NotNull VirtualFile file, @NotNull String moduleName, @NotNull PsiManager psiManager)
	{
		if (file.isDirectory())
		{
			for (VirtualFile child : file.getChildren())
			{
				C3Module match = findModuleInVirtualFile(child, moduleName, psiManager);
				if (match != null) return match;
			}
			return null;
		}

		String ext = file.getExtension();
		if ("c3".equals(ext) || "c3i".equals(ext))
		{
			PsiFile psi = psiManager.findFile(file);
			if (psi instanceof C3File)
			{
				for (C3Module mod : PsiTreeUtil.findChildrenOfType(psi, C3Module.class))
				{
					ModuleName mn = mod.getModuleName();
					if (mn != null && moduleName.equals(mn.getValue()))
					{
						return mod;
					}
				}
			}
		}
		return null;
	}

	private static class C3ImportPathReference extends C3ReferenceBase<C3ImportPath>
	{
		C3ImportPathReference(@NotNull C3ImportPath element)
		{
			super(element);
		}

		@Override
		public @NotNull Collection<C3PsiElement> multiResolve()
		{
			String targetModuleName = myElement.getText();
			if (com.intellij.openapi.project.DumbService.isDumb(myElement.getProject()))
			{
				C3Module direct = findModuleDirectly(targetModuleName, myElement.getProject());
				return direct != null ? List.of(direct) : Collections.emptyList();
			}
			Collection<C3PsiElement> elements = StubIndex.getElements(
				ModuleIndex.KEY,
				targetModuleName,
				myElement.getProject(),
				C3ProjectService.getInstance(myElement.getProject()).getSearchScope(),
				C3PsiElement.class)
				.stream()
				.filter(C3Module.class::isInstance)
				.toList();

			if (!elements.isEmpty())
			{
				return elements;
			}

			C3Module direct = findModuleDirectly(targetModuleName, myElement.getProject());
			return direct != null ? List.of(direct) : Collections.emptyList();
		}

		@Override
		public @NotNull TextRange getRangeInElement()
		{
			return TextRange.from(0, myElement.getTextLength());
		}
	}
}
