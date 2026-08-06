package com.privateboard.clinical
import org.junit.Assert.*
import org.junit.Test
class CustomSourceLogicTest{
 @Test fun ocrFallbackForShortTextOrNoParsedQuestions(){assertTrue(CustomSourceLogic.shouldUseOcr("scan",1));assertTrue(CustomSourceLogic.shouldUseOcr("x".repeat(200),0));assertFalse(CustomSourceLogic.shouldUseOcr("x".repeat(200),2))}
 @Test fun parsesQuestionsWithAndWithoutAnswers(){val text="Q1. Best test?\nA. One\nB. Two\nAnswer: B\nExplanation: Because.\nQ2. Unanswered item?\nA. X\nB. Y";val qs=CustomQuestionParser.parse(text,-1,100);assertEquals(2,qs.size);assertTrue(qs[0].choices[1].correct);assertTrue(qs[1].choices.none{it.correct})}
 @Test fun validatesOpenRouterResponseAndMissingFilter(){val q=Question(1,-2,null,"Custom","sba","custom","Stem","","",emptyList(),listOf(Choice(1,1,"A",false,"",null,null),Choice(2,2,"B",false,"",null,null)));assertEquals(1,AiAnswerLogic.missingAnswers(listOf(q)).size);val wrapped="""{"choices":[{"message":{"content":"{\"answer\":[\"B\"],\"explanation\":\"Reason\",\"confidence\":0.8}"}}]}""";assertEquals(setOf("B"),AiAnswerLogic.parse(wrapped,setOf("A","B"))?.letters);assertNull(AiAnswerLogic.parse(wrapped,setOf("A")))}
}
