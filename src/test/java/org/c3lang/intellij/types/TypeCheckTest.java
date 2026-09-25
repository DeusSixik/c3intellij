package org.c3lang.intellij.types;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class TypeCheckTest extends BasePlatformTestCase
{
    public void testStringIntoUintIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn void foo()
            {
                uint x = "s";
            }
            """), "Cannot assign 'String' to 'uint'.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testNegativeIntoUintIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn void foo()
            {
                uint x = -1;
            }
            """), "does not fit in type 'uint'");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testIntLiteralIntoUintOk()
    {
        assertNoTypeErrors("""
            module test;
            fn void foo()
            {
                uint x = 5;
                uint y = 300 + 200;
            }
            """);
    }

    public void testFloatIntoIntIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn void foo()
            {
                int x = 2.0;
            }
            """), "Cannot assign");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testIntIntoFloatOk()
    {
        assertNoTypeErrors("""
            module test;
            fn void foo()
            {
                double d = 5;
                float f = 1.5;
            }
            """);
    }

    public void testStringLiteralIntoStringOk()
    {
        assertNoTypeErrors("""
            module test;
            fn void foo()
            {
                String s = "hi";
            }
            """);
    }

    public void testAssignmentIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn void foo()
            {
                uint x;
                x = "s";
            }
            """), "Cannot assign 'String' to 'uint'.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testCompoundAssignmentOk()
    {
        assertNoTypeErrors("""
            module test;
            fn void foo()
            {
                uint x = 5;
                x += 1;
            }
            """);
    }

    public void testReturnWrongTypeIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn uint foo()
            {
                return "s";
            }
            """), "Cannot return 'String' from function returning 'uint'.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testBareReturnIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn uint foo()
            {
                return;
            }
            """), "Expected to return a value of type 'uint'.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testReturnValueInVoidIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn void foo()
            {
                return 1;
            }
            """), "Cannot return a value from a void function.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testMissingReturnIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn uint foo()
            {
                int x = 1;
            }
            """), "Missing return of type 'uint' in function 'foo'.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testCorrectReturnsOk()
    {
        assertNoTypeErrors("""
            module test;
            fn uint foo(uint x)
            {
                if (x == 1)
                {
                    return 1;
                }
                return x;
            }
            fn void bar()
            {
                return;
            }
            """);
    }

    public void testInfiniteLoopSkipsMissingReturn()
    {
        assertNoTypeErrors("""
            module test;
            fn uint foo()
            {
                while (true) {}
            }
            """);
    }

    public void testCastSuppressesError()
    {
        assertNoTypeErrors("""
            module test;
            fn uint foo(String s)
            {
                uint y = (uint)s;
                return y;
            }
            """);
    }

    public void testWideningOkNarrowingError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn void foo(int a, long b)
            {
                long x = a;
                int y = b;
            }
            """), "Cannot assign 'long' to 'int'.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testCallReturnTypeOk()
    {
        assertNoTypeErrors("""
            module test;
            fn uint get();
            fn void foo()
            {
                uint x = get();
            }
            """);
    }

    public void testCallReturnTypeMismatchIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn String name();
            fn void foo()
            {
                uint x = name();
            }
            """), "Cannot assign 'String' to 'uint'.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testNullPointerOk()
    {
        assertNoTypeErrors("""
            module test;
            fn void foo()
            {
                int* p = null;
            }
            """);
    }

    public void testNullIntoIntIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn void foo()
            {
                int x = null;
            }
            """), "Cannot assign");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testBoolCompareReturnOk()
    {
        assertNoTypeErrors("""
            module test;
            fn bool foo(uint x)
            {
                return x == 1;
            }
            """);
    }

    public void testBoolIntoIntIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn void foo()
            {
                int x = true;
            }
            """), "Cannot assign");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testStructFieldAssignmentIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            struct Person
            {
                uint age;
            }
            fn void foo()
            {
                Person p;
                p.age = "s";
            }
            """), "Cannot assign 'String' to 'uint'.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testStructValueAssignmentOk()
    {
        assertNoTypeErrors("""
            module test;
            struct Baz
            {
                int x;
            }
            fn void foo()
            {
                Baz b;
                Baz b2 = b;
            }
            """);
    }

    public void testEnumAccessOk()
    {
        assertNoTypeErrors("""
            module test;
            enum Color
            {
                RED,
                GREEN,
            }
            fn void foo()
            {
                Color c = Color.RED;
            }
            """);
    }

    public void testEnumIntoUintIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            enum Color
            {
                RED,
                GREEN,
            }
            fn void foo()
            {
                uint u = Color.RED;
            }
            """), "Cannot assign");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testCharLiteralOk()
    {
        assertNoTypeErrors("""
            module test;
            fn void foo()
            {
                char c = 'a';
                uint u = 'a';
            }
            """);
    }

    public void testAnyAcceptsAnything()
    {
        assertNoTypeErrors("""
            module test;
            fn void foo()
            {
                any x = 5;
                any y = "s";
                any z = x;
            }
            """);
    }

    public void testAliasOk()
    {
        assertNoTypeErrors("""
            module test;
            alias CharPtr = char*;
            fn void foo()
            {
                CharPtr p = null;
                char* q = p;
                CharPtr r = q;
            }
            """);
    }

    public void testAliasMismatchReportsUnderlyingType()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            alias Numbers = int[10];
            fn void foo()
            {
                Numbers n = "s";
            }
            """), "Cannot assign 'String' to 'int[10]'.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testTypedefLiteralOk()
    {
        assertNoTypeErrors("""
            module test;
            typedef Foo = int;
            fn void foo()
            {
                Foo f = 0;
            }
            """);
    }

    public void testTypedefNonLiteralIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            typedef Foo = int;
            fn void foo(int i)
            {
                Foo f = i;
            }
            """), "Cannot assign 'int' to 'Foo'.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testInlineTypedefConvertsToBase()
    {
        assertNoTypeErrors("""
            module test;
            typedef ZString = inline char*;
            fn void foo(ZString z)
            {
                char* p = z;
            }
            """);
    }

    public void testVectorInitOk()
    {
        assertNoTypeErrors("""
            module test;
            fn void foo()
            {
                int[<2>] c = { 23, 11 };
            }
            """);
    }

    public void testVectorInitElementMismatchIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn void foo()
            {
                int[<2>] c = { 1, "s" };
            }
            """), "Cannot assign 'String' to 'int[<2>]'.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testVectorInitCountMismatchIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn void foo()
            {
                int[<2>] c = { 1, 2, 3 };
            }
            """), "Expected 2 elements for type 'int[<2>]' but got 3.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testVectorScalarWideningOk()
    {
        assertNoTypeErrors("""
            module test;
            fn void foo()
            {
                int[<2>] d = { 21, 14 };
                int[<2>] e = d / 7;
                int[<2>] f = 4;
            }
            """);
    }

    public void testVectorArithmeticOk()
    {
        assertNoTypeErrors("""
            module test;
            fn void foo()
            {
                int[<2>] a = { 23, 11 };
                int[<2>] b = { 2, 1 };
                int[<2>] c = a * b;
            }
            """);
    }

    public void testVectorIntoScalarIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn void foo()
            {
                int[<2>] v = { 1, 2 };
                int x = v;
            }
            """), "Cannot assign");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    private @NotNull List<HighlightInfo> check(@NotNull String code)
    {
        myFixture.configureByText("main.c3", code);
        return myFixture.doHighlighting();
    }

    private void assertNoTypeErrors(@NotNull String code)
    {
        List<HighlightInfo> typeErrors = new ArrayList<>();
        for (HighlightInfo info : check(code))
        {
            if (info.getSeverity() == HighlightSeverity.ERROR && info.getDescription() != null)
            {
                String description = info.getDescription();
                if (description.contains("Cannot assign")
                    || description.contains("Cannot return")
                    || description.contains("does not fit in type")
                    || description.contains("Expected to return")
                    || description.contains("Missing return"))
                {
                    typeErrors.add(info);
                }
            }
        }
        assertTrue("Unexpected type errors, got: " + typeErrors, typeErrors.isEmpty());
    }

    private static @NotNull List<HighlightInfo> errorsWithText(@NotNull List<HighlightInfo> highlights, @NotNull String textPart)
    {
        List<HighlightInfo> result = new ArrayList<>();
        for (HighlightInfo info : highlights)
        {
            if (info.getSeverity() == HighlightSeverity.ERROR
                && info.getDescription() != null
                && info.getDescription().contains(textPart))
            {
                result.add(info);
            }
        }
        return result;
    }
}
