package org.c3lang.intellij.annotation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.c3lang.intellij.C3SyntaxHighlighter;

import java.util.List;

public class C3AnnotatorTest extends BasePlatformTestCase
{
	public void testSemanticHighlighting()
	{
		myFixture.configureByText("main.c3", """
			module std::collections::enummap;
			import std::io;

			struct EnumMap
			{
				int[] values;
			}

			fn void EnumMap.init(EnumMap* this, int init_value)
			{
				foreach(&a : this.values)
				{
					*a = init_value;
				}
				io::printn("hello");
			}

			struct MyTr
			{
				int a;
			}

			fn int MyTr.test(MyTr* self)
			{
				return self.a;
			}

			fn void caller(MyTr tr)
			{
				tr.test();
			}
			""");

		List<HighlightInfo> highlights = myFixture.doHighlighting();
		assertNotNull(highlights);

		assertHasHighlight(highlights, "enummap", C3SyntaxHighlighter.MODULE_KEY);
		assertHasHighlight(highlights, "collections", C3SyntaxHighlighter.MODULE_KEY);
		assertHasHighlight(highlights, "io", C3SyntaxHighlighter.MODULE_KEY);
		assertHasHighlight(highlights, "this", C3SyntaxHighlighter.PARAMETER_KEY);
		assertHasHighlight(highlights, "init_value", C3SyntaxHighlighter.PARAMETER_KEY);
		assertHasHighlight(highlights, "a", C3SyntaxHighlighter.LOCAL_VARIABLE_KEY);
		assertHasHighlight(highlights, "values", C3SyntaxHighlighter.FIELD_KEY);
		assertHasHighlight(highlights, "printn", C3SyntaxHighlighter.FUNCTION_CALL_KEY);
		assertHasHighlight(highlights, "self", C3SyntaxHighlighter.PARAMETER_KEY);
		assertHasHighlight(highlights, "test", C3SyntaxHighlighter.METHOD_CALL_KEY);
	}

	private static void assertHasHighlight(List<HighlightInfo> highlights, String text, TextAttributesKey key)
	{
		boolean found = highlights.stream().anyMatch(h ->
			text.equals(h.getText()) && key.equals(h.forcedTextAttributesKey)
		);
		assertTrue("Expected highlight with text '" + text + "' and key " + key.getExternalName(), found);
	}
}
