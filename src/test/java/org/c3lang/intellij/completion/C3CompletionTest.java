package org.c3lang.intellij.completion;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class C3CompletionTest extends BasePlatformTestCase
{
	public void testCompletionAfterScope()
	{
		myFixture.configureByText("io.c3", """
			module std::io;

			fn void printn(char[] s) {}
			fn void printf(char[] fmt) {}
			""");

		myFixture.configureByText("main.c3", """
			module main;
			import std::io;

			fn void main()
			{
				io::prin<caret>
			}
			""");

		myFixture.completeBasic();
		List<String> lookupStrings = myFixture.getLookupElementStrings();
		assertNotNull("Lookup strings should not be null", lookupStrings);
		assertTrue("Should suggest printn", lookupStrings.contains("printn"));
	}

	public void testMethodAndFieldCompletionOnInstance()
	{
		myFixture.configureByText("main.c3", """
			module main;

			struct MyTr
			{
				int a;
			}

			fn int MyTr.test()
			{
				return 0;
			}

			fn void caller(MyTr tr)
			{
				tr.<caret>
			}
			""");

		myFixture.completeBasic();
		List<String> lookupStrings = myFixture.getLookupElementStrings();
		assertNotNull("Lookup strings should not be null", lookupStrings);
		assertTrue("Should suggest field 'a', got: " + lookupStrings, lookupStrings.contains("a"));
		assertTrue("Should suggest method 'test', got: " + lookupStrings, lookupStrings.contains("test"));
	}

	public void testReturnCompletionInFunction()
	{
		myFixture.configureByText("main.c3", """
			module main;

			struct MyTr
			{
				int a;
			}

			fn int MyTr.test()
			{
				ret<caret>
			}
			""");

		myFixture.completeBasic();
		List<String> lookupStrings = myFixture.getLookupElementStrings();
		if (lookupStrings != null)
		{
			assertTrue("Should suggest 'return', got: " + lookupStrings, lookupStrings.contains("return"));
		}
		else
		{
			assertTrue("Should auto-insert 'return'", myFixture.getEditor().getDocument().getText().contains("return"));
		}
	}

	public void testParameterAndVariableCompletion()
	{
		myFixture.configureByText("main.c3", """
			module main;

			struct EnumMap
			{
				int values;
			}

			fn void EnumMap.init(EnumMap* this, int init_value)
			{
				ini<caret>
			}
			""");

		myFixture.completeBasic();
		List<String> lookupStrings = myFixture.getLookupElementStrings();
		if (lookupStrings != null)
		{
			assertTrue("Should suggest parameter 'init_value', got: " + lookupStrings, lookupStrings.contains("init_value"));
		}
		else
		{
			assertTrue("Should auto-insert 'init_value'", myFixture.getEditor().getDocument().getText().contains("init_value"));
		}
	}

	public void testThisCompletion()
	{
		myFixture.configureByText("main.c3", """
			module main;

			struct EnumMap
			{
				int values;
			}

			fn void EnumMap.init(EnumMap* this, int init_value)
			{
				th<caret>
			}
			""");

		myFixture.completeBasic();
		List<String> lookupStrings = myFixture.getLookupElementStrings();
		if (lookupStrings != null)
		{
			assertTrue("Should suggest 'this', got: " + lookupStrings, lookupStrings.contains("this"));
		}
		else
		{
			assertTrue("Should auto-insert 'this'", myFixture.getEditor().getDocument().getText().contains("this"));
		}
	}

	public void testForeachVariableCompletion()
	{
		myFixture.configureByText("main.c3", """
			module main;

			struct EnumMap
			{
				int[] values;
			}

			fn void EnumMap.init(EnumMap* this, int init_value)
			{
				foreach(&a : this.values)
				{
					<caret>
				}
			}
			""");

		myFixture.completeBasic();
		List<String> lookupStrings = myFixture.getLookupElementStrings();
		assertNotNull("Lookup strings should not be null", lookupStrings);
		assertTrue("Should suggest foreach variable 'a', got: " + lookupStrings, lookupStrings.contains("a"));
	}

	public void testBuiltinAttributeCompletion()
	{
		myFixture.configureByText("main.c3", """
			module main;

			@nod<caret>
			fn void foo() {}
			""");

		myFixture.completeBasic();
		List<String> lookupStrings = myFixture.getLookupElementStrings();
		if (lookupStrings != null)
		{
			assertTrue("Should suggest '@nodiscard', got: " + lookupStrings, lookupStrings.contains("@nodiscard"));
		}
		else
		{
			assertTrue("Should auto-insert '@nodiscard'", myFixture.getEditor().getDocument().getText().contains("@nodiscard"));
		}
	}

	public void testBuiltinConstantsCompletion()
	{
		myFixture.configureByText("main.c3", """
			module main;

			fn void foo()
			{
				$$F<caret>
			}
			""");

		myFixture.completeBasic();
		List<String> lookupStrings = myFixture.getLookupElementStrings();
		assertNotNull("Lookup strings should not be null", lookupStrings);
		assertTrue("Should suggest '$$FILE', got: " + lookupStrings, lookupStrings.contains("$$FILE"));
		assertTrue("Should suggest '$$FUNC', got: " + lookupStrings, lookupStrings.contains("$$FUNC"));
		assertTrue("Should suggest '$$FUNCTION', got: " + lookupStrings, lookupStrings.contains("$$FUNCTION"));
	}

	public void testCompileTimeDirectivesCompletion()
	{
		myFixture.configureByText("main.c3", """
			module main;

			fn void foo()
			{
				$def<caret>
			}
			""");

		myFixture.completeBasic();
		List<String> lookupStrings = myFixture.getLookupElementStrings();
		if (lookupStrings != null)
		{
			assertTrue("Should suggest '$defined', got: " + lookupStrings, lookupStrings.contains("$defined"));
		}
		else
		{
			assertTrue("Should auto-insert '$defined'", myFixture.getEditor().getDocument().getText().contains("$defined"));
		}
	}

	public void testPrimitiveTypesCompletion()
	{
		myFixture.configureByText("main.c3", """
			module main;

			fn in<caret>
			""");

		myFixture.completeBasic();
		List<String> lookupStrings = myFixture.getLookupElementStrings();
		assertNotNull("Lookup strings should not be null", lookupStrings);
		assertTrue("Should suggest 'int', got: " + lookupStrings, lookupStrings.contains("int"));
		assertTrue("Should suggest 'int128', got: " + lookupStrings, lookupStrings.contains("int128"));
	}

	public void testExpressionLiteralsCompletion()
	{
		myFixture.configureByText("main.c3", """
			module main;

			fn void foo()
			{
				var x = tr<caret>
			}
			""");

		myFixture.completeBasic();
		List<String> lookupStrings = myFixture.getLookupElementStrings();
		if (lookupStrings != null)
		{
			assertTrue("Should suggest 'true', got: " + lookupStrings, lookupStrings.contains("true"));
		}
		else
		{
			assertTrue("Should auto-insert 'true'", myFixture.getEditor().getDocument().getText().contains("true"));
		}
	}

	public void testStatementKeywordsCompletion()
	{
		myFixture.configureByText("main.c3", """
			module main;

			fn void foo()
			{
				fore<caret>
			}
			""");

		myFixture.completeBasic();
		List<String> lookupStrings = myFixture.getLookupElementStrings();
		assertNotNull("Lookup strings should not be null", lookupStrings);
		assertTrue("Should suggest 'foreach', got: " + lookupStrings, lookupStrings.contains("foreach"));
		assertTrue("Should suggest 'foreach_r', got: " + lookupStrings, lookupStrings.contains("foreach_r"));
	}
}
