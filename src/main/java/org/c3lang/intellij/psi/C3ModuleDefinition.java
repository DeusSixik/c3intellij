package org.c3lang.intellij.psi;

import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Objects;

public interface C3ModuleDefinition extends C3ModuleNamePsiElement
{
	@NotNull List<ModuleName> getImports();
	@NotNull List<C3ImportDecl> getImportDeclarations();
	@NotNull List<C3ImportPath> getImportPaths();

	boolean containsImportOrSameModule(@NotNull C3FullyQualifiedNamePsiElement callable);
	boolean contains(@NotNull C3PathIdent pathIdent);
	boolean contains(@NotNull C3Path path);
	@NotNull List<C3ImportPath> getImportOf(@NotNull C3PathIdent pathIdent);
	@NotNull List<C3ImportPath> getImportOf(@NotNull C3PathIdentExpr pathIdentExpr);
	@NotNull List<FullyQualifiedName> resolve(@NotNull C3PathIdentExpr pathIdent);
	@NotNull List<FullyQualifiedName> resolve(@NotNull C3Type type);
	@NotNull List<C3ImportPath> getImportPaths(@NotNull ModuleName moduleName);

	default boolean isSameModule(@Nullable ModuleName moduleName)
	{
		return Objects.equals(getModuleName(), moduleName);
	}

	default boolean isParentModule(@Nullable ModuleName moduleName)
	{
		ModuleName currentModule = getModuleName();
		return moduleName != null
			&& currentModule != null
			&& moduleName.covers(currentModule)
			&& !moduleName.equals(currentModule);
	}

	default @Nullable ModuleName getImportedModuleCovering(@Nullable ModuleName moduleName)
	{
		ModuleName best = null;
		for (ModuleName imported : getImports())
		{
			if (imported.covers(moduleName)
				&& (best == null || imported.getValue().length() > best.getValue().length()))
			{
				best = imported;
			}
		}
		return best;
	}

	default @Nullable ModuleName getPublicImportedModuleCovering(@Nullable ModuleName moduleName)
	{
		ModuleName best = null;
		for (C3ImportPath importPath : getImportPaths())
		{
			if (!importPath.isPublicImport()) continue;

			ModuleName imported = importPath.getModuleName();
			if (imported != null
				&& imported.covers(moduleName)
				&& (best == null || imported.getValue().length() > best.getValue().length()))
			{
				best = imported;
			}
		}
		return best;
	}

	default @Nullable ModuleName getVisibleModulePrefix(@Nullable ModuleName moduleName)
	{
		if (isSameModule(moduleName)) return moduleName;
		if (isParentModule(moduleName)) return moduleName;

		ModuleName autoImported = ModuleName.autoImportedPrefix(moduleName);
		if (autoImported != null) return autoImported;

		return getImportedModuleCovering(moduleName);
	}

	default boolean isVisible(@NotNull C3FullyQualifiedNamePsiElement element)
	{
		ModuleName moduleName = element.getModuleName();
		if (isSameModule(moduleName)) return true;

		// Stub-first `@private` check: reading attributes off index-backed
		// PSI would force AST loads during index reads
		// (UpToDateStubIndexMismatch).
		if (!org.c3lang.intellij.stubs.StubPrivacy.isPrivate(element))
		{
			return getVisibleModulePrefix(moduleName) != null;
		}

		return getPublicImportedModuleCovering(moduleName) != null;
	}
}
