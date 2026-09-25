package org.c3lang.intellij.psi.reference;

import com.intellij.psi.PsiElement;
import org.c3lang.intellij.index.InterfaceService;
import org.c3lang.intellij.psi.C3InterfaceDefinition;
import org.c3lang.intellij.psi.C3PsiElement;
import org.c3lang.intellij.psi.C3TypeName;
import org.c3lang.intellij.psi.FullyQualifiedName;
import org.c3lang.intellij.psi.ModuleName;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Reference from an interface name in a struct contract, e.g. {@code MyName}
 * in {@code struct Baz (MyName)}, to the {@link C3InterfaceDefinition}.
 */
public class C3InterfaceReference extends C3ReferenceBase<C3TypeName>
{
    public C3InterfaceReference(@NotNull C3TypeName element)
    {
        super(element);
    }

    public static @NotNull FullyQualifiedName interfaceNameOf(@NotNull C3TypeName element)
    {
        String text = element.getText().strip();
        if (text.contains("::")) return FullyQualifiedName.parse(text);
        return new FullyQualifiedName(ModuleName.from(element), text);
    }

    @Override
    public @NotNull Collection<C3PsiElement> multiResolve()
    {
        String text = myElement.getText();
        if (text == null || text.isBlank()) return Collections.emptyList();
        List<C3PsiElement> result = new ArrayList<>(
            InterfaceService.INSTANCE.findInterfaceDefinitions(
                interfaceNameOf(myElement),
                myElement.getProject()));
        return result;
    }

    @Override
    public boolean isReferenceTo(@NotNull PsiElement element)
    {
        if (element instanceof C3InterfaceDefinition definition)
        {
            FullyQualifiedName target = new FullyQualifiedName(
                ModuleName.from(definition),
                definition.getTypeName().getText().strip());
            return target.equals(interfaceNameOf(myElement));
        }
        return super.isReferenceTo(element);
    }
}
