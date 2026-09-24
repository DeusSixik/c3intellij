package org.c3lang.intellij.index;

import com.intellij.openapi.project.Project;
import com.intellij.psi.stubs.StubIndex;
import org.c3lang.intellij.project.C3ProjectService;
import org.c3lang.intellij.psi.C3CallablePsiElement;
import org.c3lang.intellij.psi.C3BaseType;
import org.c3lang.intellij.psi.C3FullyQualifiedNamePsiElement;
import org.c3lang.intellij.psi.C3PsiElement;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class NameIndexService
{
    public static final NameIndexService INSTANCE = new NameIndexService();

    private NameIndexService()
    {
    }

    @NotNull
    public Collection<C3FullyQualifiedNamePsiElement> findByNameEndsWith(@NotNull String name, @NotNull Project project)
    {
        List<C3FullyQualifiedNamePsiElement> result = new ArrayList<>();
        for (String key : StubIndex.getInstance().getAllKeys(NameIndex.KEY, project))
        {
            if (key.endsWith(name))
            {
                for (C3PsiElement element : getElementsByName(key, project))
                {
                    if (element instanceof C3FullyQualifiedNamePsiElement named)
                    {
                        result.add(named);
                    }
                }
            }
        }
        return result;
    }

    @NotNull
    public Collection<C3CallablePsiElement> findMethodsForType(@NotNull org.c3lang.intellij.psi.FullyQualifiedName type, @org.jetbrains.annotations.Nullable String methodName, @NotNull Project project)
    {
        List<C3CallablePsiElement> result = new ArrayList<>();
        String suffix = methodName != null ? "." + methodName : null;
        String typeName = type.getName();
        for (String key : StubIndex.getInstance().getAllKeys(NameIndex.KEY, project))
        {
            if (suffix != null && !key.endsWith(suffix)) continue;
            for (C3PsiElement element : getElementsByName(key, project))
            {
                if (element instanceof C3CallablePsiElement callable
                    && callable.getType() != null)
                {
                    String targetTypeName = callable.getType().getValue();
                    if (targetTypeName.equals(typeName) || targetTypeName.equals(type.getFullName()))
                    {
                        if (suffix == null || callable.getFqName().getName().endsWith(suffix))
                        {
                            result.add(callable);
                        }
                    }
                }
            }
        }
        return result;
    }

    @NotNull
    public Collection<C3CallablePsiElement> findMethodsByName(@NotNull String name, @NotNull Project project)
    {
        List<C3CallablePsiElement> result = new ArrayList<>();
        for (C3FullyQualifiedNamePsiElement element : findByNameEndsWith(name, project))
        {
            if (element instanceof C3CallablePsiElement callable
                && callable.getType() != null
                && callable.getFqName().getName().endsWith("." + name))
            {
                result.add(callable);
            }
        }
        return result;
    }

    @NotNull
    public Collection<C3FullyQualifiedNamePsiElement> findType(@NotNull C3BaseType type, @NotNull Project project)
    {
        String name;
        if (type.getPath() == null)
        {
            String module = type.getModuleDefinition().getModuleName() != null
                ? type.getModuleDefinition().getModuleName().getValue()
                : null;
            name = module != null ? module + "::" + type.getText() : type.getText();
        }
        else
        {
            name = type.getText();
        }

        List<C3FullyQualifiedNamePsiElement> result = new ArrayList<>();
        for (String key : StubIndex.getInstance().getAllKeys(NameIndex.KEY, project))
        {
            if (key.endsWith(name))
            {
                for (C3PsiElement element : getElementsByName(key, project))
                {
                    if (element instanceof C3FullyQualifiedNamePsiElement named)
                    {
                        result.add(named);
                    }
                }
            }
        }
        return result;
    }

    @NotNull
    private Collection<C3PsiElement> getElementsByName(@NotNull String string, @NotNull Project project)
    {
        return StubIndex.getElements(
            NameIndex.KEY,
            string,
            project,
            C3ProjectService.getInstance(project).getSearchScope(),
            C3PsiElement.class
        );
    }
}
