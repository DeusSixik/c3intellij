package org.c3lang.intellij.psi;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Collection;

public class LocalUsagesTest extends BasePlatformTestCase
{
    public void testInterfaceMethodPreferredOverImpl()
    {
        myFixture.addFileToProject("alloc.c3", """
            module test;
            interface WallocX
            {
                fn void*? grabX(usz size);
            }
            struct ArenaWallocX (WallocX)
            {
                int x;
            }
            fn void*? ArenaWallocX.grabX(&self, usz size) @dynamic
            {
                return null;
            }
            fn void*? LazyTempWallocX.grabX(int* self, usz size)
            {
                return null;
            }
            """);
        myFixture.configureByText("main.c3", """
            module test;
            macro void*? calloc_try(WallocX allocator, usz size)
            {
                return allocator.grabX(size);
            }
            """);
        Collection<C3AccessIdent> idents = PsiTreeUtil.findChildrenOfType(myFixture.getFile(), C3AccessIdent.class);
        assertEquals(1, idents.size());
        C3AccessIdent ident = idents.iterator().next();
        PsiElement first = ident.getReference().resolve();
        assertNotNull(first);
        assertTrue("expected interface method first, got: " + first.getText(),
            first.getParent() != null && first.getParent().getParent() instanceof C3InterfaceDefinition);
    }

    public void testTypeNameResolvesToInterface()
    {
        myFixture.addFileToProject("alloc.c3", """
            module test;
            interface WallocX
            {
                fn void*? grabX(usz size);
            }
            struct ArenaWallocX (WallocX)
            {
                int x;
            }
            """);
        myFixture.configureByText("main.c3", """
            module test;
            struct Holder (WallocX)
            {
                int x;
            }
            """);
        Collection<C3TypeName> names = PsiTreeUtil.findChildrenOfType(myFixture.getFile(), C3TypeName.class);
        C3TypeName allocator = null;
        for (C3TypeName name : names)
        {
            if (name.getText().equals("WallocX"))
            {
                allocator = name;
            }
        }
        assertNotNull(allocator);
        PsiReference ref = allocator.getReference();
        assertNotNull(ref);
        PsiElement target = ref.resolve();
        assertNotNull(target);
        assertTrue("expected interface definition, got: " + target.getClass(),
            target instanceof C3InterfaceDefinition);
    }
}
