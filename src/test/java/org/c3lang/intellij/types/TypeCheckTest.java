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

    public void testNoreturnCallSkipsMissingReturn()
    {
        assertNoTypeErrors("""
            module test;
            struct JmpBuf { int x; }
            macro void unreachable(String message) @noreturn
            {
            }
            fn int setjmp(JmpBuf* buffer)
            {
                unreachable("setjmp unavailable");
            }
            """);
    }

    public void testNoreturnFunctionNeedsNoReturn()
    {
        assertNoTypeErrors("""
            module test;
            fn int fatal(int code) @noreturn
            {
                int x = code;
            }
            """);
    }

    public void testIfElseNoreturnSkipsMissingReturn()
    {
        assertNoTypeErrors("""
            module test;
            macro void unreachable(String message) @noreturn
            {
            }
            fn int foo(int x)
            {
                if (x > 0)
                {
                    unreachable("positive");
                }
                else
                {
                    unreachable("negative");
                }
            }
            """);
    }

    public void testIfWithoutElseStillNeedsReturn()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            macro void unreachable(String message) @noreturn
            {
            }
            fn int foo(int x)
            {
                if (x > 0)
                {
                    unreachable("positive");
                }
            }
            """), "Missing return of type 'int' in function 'foo'.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
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

    public void testIntToUszWideningOk()
    {
        assertNoTypeErrors("""
            module test;
            fn void take(usz elements) {}
            fn void foo(int len)
            {
                take(len);
                usz u = len;
            }
            """);
    }

    public void testUszToIszSameWidthOk()
    {
        assertNoTypeErrors("""
            module test;
            fn void take(isz x) {}
            fn void foo(usz u)
            {
                take(u);
                isz i = u;
            }
            """);
    }

    public void testUintMemberToUszOk()
    {
        assertNoTypeErrors("""
            module test;
            struct Entry
            {
                uint count;
            }
            fn void take(usz elements) {}
            fn void foo(Entry* e)
            {
                take(e.count);
                usz u = e.count;
            }
            """);
    }

    public void testCharToIntWideningOk()
    {
        assertNoTypeErrors("""
            module test;
            fn void foo(char c)
            {
                int x = c;
                uint u = c;
            }
            """);
    }

    public void testNarrowingStillError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn void foo(usz u, int i)
            {
                uint x = u;
                char c = i;
            }
            """), "Cannot assign");
        assertEquals("Expected two errors, got: " + errors, 2, errors.size());
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

    public void testAddressOfTemporaryOk()
    {
        assertNoTypeErrors("""
            module test;
            fn void va_variants_explicit(any... args) {}
            fn void foo()
            {
                int x = 1;
                any v = &x;
                va_variants_explicit(&&1, &x, v);
            }
            """);
    }

    public void testArrowOperatorIsError()
    {
        myFixture.configureByText("main.c3", """
            module test;
            struct Foo { int x; }
            fn void f()
            {
                Foo* fp;
                fp->x = 1;
            }
            """);

        List<HighlightInfo> infos = myFixture.doHighlighting();
        boolean found = infos.stream().anyMatch(info ->
            info.getSeverity() == HighlightSeverity.ERROR
                && info.getDescription() != null
                && info.getDescription().contains("->"));
        assertTrue("Expected a parse error mentioning '->', got: " + infos, found);

        // The dot syntax must work for both values and pointers.
        assertNoTypeErrors("""
            module test;
            struct Foo { int x; }
            fn void f()
            {
                Foo f2;
                f2.x = 1;
                Foo* fp = &f2;
                fp.x = 2;
                int y = fp.x;
            }
            """);
    }

    public void testMacroWithoutAtPrefixIsError()
    {
        myFixture.configureByText("main.c3", """
            module test;
            macro void badswap(a, b)
            {
                var temp = a;
            }
            macro void swap2(&x)
            {
            }
            """);

        List<HighlightInfo> infos = myFixture.doHighlighting();
        List<String> errors = new ArrayList<>();
        for (HighlightInfo info : infos)
        {
            if (info.getSeverity() == HighlightSeverity.ERROR && info.getDescription() != null
                && info.getDescription().contains("must have a name starting with '@'"))
            {
                errors.add(info.getDescription());
            }
        }
        assertEquals("Expected one macro-name error, got: " + errors, 1, errors.size());
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

    public void testSliceConversionsOk()
    {
        assertNoTypeErrors("""
            module test;
            fn void test()
            {
                int[4] arr = { 1, 2, 3, 4 };
                int[4]* ptr = &arr;
                int[] slice1 = &arr;
                int[] slice2 = ptr;
                int[] slice3 = slice1;
                int* int_ptr = slice1;
            }
            """);
    }

    public void testArrayDoesNotDecayOk()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn void test()
            {
                int[3] x = { 1, 2, 3 };
                int[] s = x;
            }
            """), "Cannot assign");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testArrayPointerNeedsCastIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn void test()
            {
                int* p;
                int[4]* ap = p;
            }
            """), "Cannot assign");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testWildcardArrayInitOk()
    {
        assertNoTypeErrors("""
            module test;
            fn void foo()
            {
                int[*] b = { 4, 5, 6 };
            }
            """);
    }

    public void testVoidPointerWildcardOk()
    {
        assertNoTypeErrors("""
            module test;
            fn void take(void* ptr) {}
            fn void foo()
            {
                int x = 1;
                int* p = &x;
                int[4] arr = { 1, 2, 3, 4 };
                int[4]* ap = &arr;
                int[] s = &arr;
                take(p);
                take(ap);
                take(s);
                take(&arr);
                take(&x);
                take(null);
            }
            """);
    }

    public void testVoidPointerMemberSliceOk()
    {
        assertNoTypeErrors("""
            module test;
            struct Tf
            {
                int[] a;
            }
            fn void methodTest(void* ptr) {}
            fn void foo()
            {
                Tf tf;
                methodTest(tf.a);
                int[] s = tf.a;
            }
            """);
    }

    public void testVoidPointerRejectsValues()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn void take(void* ptr) {}
            fn void foo()
            {
                int x = 1;
                int[4] arr = { 1, 2, 3, 4 };
                take(x);
                take(arr);
            }
            """), "Cannot pass");
        assertEquals("Expected two errors, got: " + errors, 2, errors.size());
    }

    public void testVoidPointerThroughTypedefOk()
    {
        assertNoTypeErrors("""
            module test;
            alias Vp = void*;
            typedef Vp2 = void*;
            fn void foo()
            {
                void* pr;
                int* pr_i = pr;
                char* pr_c = (void*)pr_i;
                Vp v;
                int* a = v;
                Vp2 w;
                int* b = w;
                Vp2 back = pr_i;
                Vp2 n = null;
            }
            """);
    }

    public void testPointerToPointerNeedsCast()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn void foo()
            {
                void* pr;
                int* pr_i = pr;
                char* pr_c = pr_i;
            }
            """), "Cannot assign 'int*' to 'char*'.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testIntIntoTypedefVoidPointerIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            typedef Vp2 = void*;
            fn void foo()
            {
                Vp2 x = 5;
            }
            """), "Cannot assign");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testStructPointerToInterfaceOk()
    {
        assertNoTypeErrors("""
            module test;
            interface OutStream
            {
            }
            struct File (OutStream)
            {
                int x;
            }
            fn File* stderr();
            fn void foo()
            {
                OutStream stream = stderr();
                File f;
                OutStream s2 = &f;
            }
            """);
    }

    public void testUnrelatedPointerToInterfaceIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            interface OutStream
            {
            }
            struct Other
            {
                int x;
            }
            fn Other* make();
            fn void foo()
            {
                OutStream s = make();
            }
            """), "Cannot assign 'Other*' to 'OutStream'.");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testOptionalCastLiftsToOptional()
    {
        assertNoTypeErrors("""
            module test;
            faultdef SEEK_FAIL;
            fn long? native_ftell(int file)
            {
                return -1;
            }
            fn usz? seeker(int file)
            {
                return (usz)native_ftell(file);
            }
            """);
    }

    public void testOptionalCastToPlainIsError()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn long? maybe();
            fn void foo()
            {
                usz x = (usz)maybe();
            }
            """), "Cannot assign 'usz?' to 'usz'");
        assertEquals("Expected one error, got: " + errors, 1, errors.size());
    }

    public void testLiftedCastResultIsOptional()
    {
        List<HighlightInfo> errors = errorsWithText(check("""
            module test;
            fn long? maybe();
            fn void foo()
            {
                usz x = (usz)maybe();
            }
            """), "Cannot assign 'usz?' to 'usz'");
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
